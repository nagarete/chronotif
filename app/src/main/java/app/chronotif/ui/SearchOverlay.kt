// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.chronotif.CalendarEvent
import app.chronotif.Contact
import app.chronotif.ClockActions
import app.chronotif.ExchangeRates
import app.chronotif.Files
import app.chronotif.FoundFile
import app.chronotif.Handoff
import app.chronotif.Launchables
import app.chronotif.Launchable
import app.chronotif.PersonalData
import app.chronotif.R
import app.chronotif.Prefs
import app.chronotif.SettingsPages
import app.chronotif.core.Answer
import app.chronotif.core.AppMatch
import app.chronotif.core.ClockCommand
import app.chronotif.core.Money
import app.chronotif.core.Numbers
import app.chronotif.core.PersonalMatch
import app.chronotif.core.TypedNumber
import app.chronotif.core.Usage
import app.chronotif.core.WebQuery

/**
 * Search (ADR 0008, ADR 0023), laid over the panel, the lock-screen view or its own window. Before
 * typing: the apps that notified most recently. Typing: an answer (math, units, time in a place,
 * a timer or alarm), apps, contacts or a typed number, Settings pages, calendar events, Files and,
 * last, the web with the hand-off rows. It never searches notifications. Opening anything goes
 * through [NotifList.Host.guarded], so on the lock screen it unlocks first; copying an answer and
 * setting a timer or alarm don't. Contacts, events and Files show while locked, unless the lock
 * screen hides content.
 */
@SuppressLint("ViewConstructor")
class SearchOverlay private constructor(
    context: Context,
    private val host: NotifList.Host,
    initialQuery: String,
    private val onClosed: () -> Unit,
) : FrameLayout(context), Overlay {

    companion object {
        /**
         * Where typed queries are matched: off the main thread, so a phone with thousands of
         * contacts or hundreds of apps still types smoothly. Its own lane, so a slow system call
         * on [app.chronotif.Background] never holds up results.
         */
        private val lane = app.chronotif.Lane("chronotif-search")

        /** Lays Search over [anchor]'s window. Nothing happens if something already covers it. */
        fun show(anchor: View, host: NotifList.Host, query: String = "", onClosed: () -> Unit = {}): SearchOverlay? {
            val root = anchor.rootView as? ViewGroup ?: return null
            if ((0 until root.childCount).any { root.getChildAt(it) is Overlay }) return null
            val v = SearchOverlay(anchor.context, host, query, onClosed)
            root.addView(v, ViewGroup.LayoutParams(-1, -1))
            return v
        }
    }

    private val pal = Palette(context)
    private val column = LinearLayout(context)
    private val suggestionsBlock = LinearLayout(context)
    private val suggestionsRow = LinearLayout(context)
    private val resultsScroll = ScrollView(context)
    /** Answer, apps, contacts, Settings pages, events, Files and the web rows, each its own card, in that order. */
    private val results = LinearLayout(context)
    private val numbers = Numbers(context.resources.configuration.locales[0])
    private val spacer = View(context)
    private val field = EditText(context)
    private val fieldBox = LinearLayout(context)
    private var apps: List<Launchable> = emptyList()
    /** Search's lists with their names folded once. Only read and written on [lane]. */
    private var appKeys: List<AppKey> = emptyList()
    private var contactKeys: List<ContactKey> = emptyList()
    private var eventKeys: List<EventKey> = emptyList()
    /** The latest query handed to [lane]; a match for an older one is dropped. */
    @Volatile private var asked = 0
    /** What's on screen now, for "Show more" and Go. */
    private var shown: Found? = null
    /** "Show more" was tapped for this query's contacts. */
    private var allContacts = false
    /** "Show more" was tapped for this query's Files. */
    private var allFiles = false
    /** Apps showing for this query; "Show more" adds [AppMatch.MORE]. */
    private var appsShown = AppMatch.SHOWN
    private var shownQuery = ""
    private var closing = false
    /** Data arrived while off screen; shown once back on it. */
    private var stale = false

    private class AppKey(val app: Launchable, val key: AppMatch.Key)
    private class ContactKey(val contact: Contact, val key: AppMatch.Key, val digits: List<String>)
    private class EventKey(val event: CalendarEvent, val key: AppMatch.Key)

    /** What matched one query, found on [lane]. */
    private class Found(
        val query: String,
        val apps: List<Launchable>,
        val people: List<Contact>,
        val pages: List<SettingsPages.Page>,
        val events: List<CalendarEvent>,
        val files: List<FoundFile>,
        val hidePersonal: Boolean,
    )

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null

    init {
        setBackgroundColor(if (Build.VERSION.SDK_INT >= 31) 0x80000000.toInt() else 0xE6000000.toInt())
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        // Taps on empty space close it.
        setOnClickListener { close() }

        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(12), 0, dp(12), 0)

        suggestionsBlock.orientation = LinearLayout.VERTICAL
        suggestionsBlock.addView(Ui.text(context, 13f, pal.onScrimSecondary, bold = true, lines = 1).apply {
            setText(R.string.search_suggestions)
            Ui.shadow(this)
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        suggestionsRow.apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rounded(pal.card, dp(22).toFloat())
            setPadding(dp(6), dp(12), dp(6), dp(10))
            isClickable = true // Taps between icons don't fall through and close.
        }
        suggestionsBlock.addView(suggestionsRow, Ui.lp())
        suggestionsBlock.visibility = GONE
        column.addView(suggestionsBlock, Ui.lp())

        results.orientation = LinearLayout.VERTICAL
        resultsScroll.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Taps below the results close it, as on the backdrop.
                setOnClickListener { close() }
                addView(results, Ui.lp())
            }, LayoutParams(-1, -2))
            visibility = GONE
        }
        column.addView(resultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Keeps the field at the bottom while only the suggestions show.
        column.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))

        fieldBox.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(pal.card, dp(14).toFloat())
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(pal.textSecondary)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
            setOnClickListener { focusField() }
        }
        field.apply {
            background = null
            setPadding(0, 0, 0, 0)
            textSize = 17f
            setTextColor(pal.text)
            setHintTextColor(pal.textSecondary)
            setHint(R.string.search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            // Go sets a typed timer or alarm, else opens the top app, or goes to the web when no app matches.
            setOnEditorActionListener { _, id, _ ->
                if (id != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                val q = text.toString()
                val clock = (Answer.of(q, numbers) as? Answer.Clock)?.command
                if (clock != null) {
                    setClock(clock)
                } else if (AppMatch.fold(q).isNotEmpty()) {
                    val top = shown?.takeIf { it.query == q }?.apps?.firstOrNull()
                        ?: AppMatch.search(apps, q) { it.label }.firstOrNull()
                    top?.let(::open) ?: openWeb(q)
                }
                true
            }
        }
        column.addView(fieldBox, Ui.lp().apply { topMargin = dp(12); bottomMargin = dp(10) })

        addView(column, LayoutParams(-1, -1))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            column.setPadding(bars.left + dp(12), bars.top + dp(16), bars.right + dp(12), maxOf(bars.bottom, ime.bottom))
            insets
        }

        if (initialQuery.isNotEmpty()) {
            field.setText(initialQuery)
            field.setSelection(initialQuery.length)
        }
        val app = context.applicationContext
        // Settings pages are looked up before the first letter, not on it.
        lane.run { runCatching { SettingsPages.prepare(app) } }
        loadApps()
        PersonalData.contacts(context) { list ->
            if (list.isEmpty()) return@contacts
            lane.run({
                contactKeys = list.map { c -> ContactKey(c, AppMatch.Key(c.name), c.numbers.map { n -> n.filter(Char::isDigit) }) }
            }) { refreshWhenShown() }
        }
        PersonalData.events(context) { list ->
            if (list.isEmpty()) return@events
            lane.run({ eventKeys = list.map { EventKey(it, AppMatch.Key(it.title)) } }) { refreshWhenShown() }
        }
    }

    private fun locked(c: Context) = c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private fun locked() = locked(context)

    private fun loadApps() = Launchables.load(context) { list ->
        apps = list
        lane.run({ appKeys = list.map { AppKey(it, AppMatch.Key(it.label)) } }) { refreshWhenShown() }
    }

    /**
     * For data that arrives later: its window may have gone (the lock-screen view finished, the
     * Panel closed) with Search still in it, and rebuilding the results then is wasted work.
     */
    private fun refreshWhenShown() {
        if (isAttachedToWindow) refresh() else stale = true
    }

    private fun refresh() {
        if (closing) return
        val query = field.text.toString()
        val ask = ++asked
        if (AppMatch.fold(query).isEmpty()) {
            shown = null
            resultsScroll.visibility = GONE
            spacer.visibility = VISIBLE
            val picks = Launchables.suggestions(apps)
            suggestionsBlock.visibility = if (picks.isEmpty()) GONE else VISIBLE
            suggestionsRow.removeAllViews()
            for (a in picks) suggestionsRow.addView(cell(a), LinearLayout.LayoutParams(0, -2, 1f))
            // Keep four columns, so fewer suggestions don't stretch apart.
            repeat(AppMatch.SUGGESTIONS - picks.size) {
                suggestionsRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            }
            return
        }
        val app = context.applicationContext
        // Typing faster than matching: only the latest query is matched, and only its result shows.
        lane.run({ if (ask == asked) find(app, query, ask) else null }) { found ->
            if (found != null && ask == asked && !closing) show(found)
        }
    }

    /** Matches [query] on [lane]; null once a newer query has been typed. */
    private fun find(c: Context, query: String, ask: Int): Found? {
        val folded = AppMatch.fold(query)
        val matched = AppMatch.rank(appKeys, folded) { it.key }.map { it.app }
        if (ask != asked) return null
        // While locked, contacts, events and Files show unless the lock screen hides content.
        val hidePersonal = locked(c) && app.chronotif.LockPolicy.hidesContent(c) &&
            (PersonalData.contactsOn(c) || PersonalData.calendarOn(c) || Files.on(c))
        val people = if (hidePersonal) emptyList()
        else PersonalMatch.rankContacts(contactKeys, query, { it.key }, { it.digits }).map { it.contact }
        if (ask != asked) return null
        val pages = runCatching { SettingsPages.search(c, query) }.getOrDefault(emptyList())
        val upcoming = if (hidePersonal) emptyList()
        else PersonalMatch.rankEvents(eventKeys, query, System.currentTimeMillis(), { it.event.id }, { it.key },
            { it.event.begin }, { it.event.end }).map { it.event }
        if (ask != asked) return null
        // Asked of Android's file index for each query: it can hold tens of thousands of files, too many to keep.
        val files = if (hidePersonal) emptyList() else Files.search(c, query)
        return Found(query, matched, people, pages, upcoming, files, hidePersonal)
    }

    /** Lays out what [find] matched, under the answer and over the web row. */
    private fun show(found: Found) {
        val query = found.query
        shown = found
        suggestionsBlock.visibility = GONE
        resultsScroll.visibility = VISIBLE
        spacer.visibility = GONE
        if (query != shownQuery) {
            allContacts = false
            allFiles = false
            appsShown = AppMatch.SHOWN
        }
        shownQuery = query
        results.removeAllViews()
        Answer.of(query, numbers)?.let { a ->
            val card = when (a) {
                is Answer.Currency -> currencyCard(a.query)
                is Answer.Clock -> clockCard(a.command)
                else -> answerCard(a)
            }
            card?.let {
                results.addView(it, cardLp())
                app.chronotif.Tutorial.notice(app.chronotif.TutorialSource.Event.ANSWER_SHOWN)
            }
        }
        // A short query can match hundreds of apps: a few show, and "Show more" adds the next ones.
        val apps = found.apps
        if (apps.isNotEmpty()) {
            val rows = apps.take(appsShown).map(::row) +
                listOfNotNull(if (apps.size > appsShown) showMoreRow(apps.size - appsShown) { appsShown += AppMatch.MORE } else null)
            results.addView(card(R.string.search_apps, rows), cardLp())
        }
        val people = found.people
        if (people.isNotEmpty()) {
            val visible = if (allContacts) people.take(PersonalMatch.CONTACTS_MAX) else people.take(PersonalMatch.CONTACTS_SHOWN)
            val rows = visible.map(::contactRow) +
                listOfNotNull(if (visible.size < people.size && !allContacts) showMoreRow(people.size - visible.size) { allContacts = true } else null)
            results.addView(card(R.string.search_contacts, rows), cardLp())
        }
        // A number no contact has can still be called or messaged.
        val typed = TypedNumber.of(query)
        if (typed != null && people.isEmpty()) results.addView(card(null, listOf(numberRow(typed))), cardLp())
        if (found.pages.isNotEmpty()) results.addView(card(R.string.search_settings, found.pages.map(::settingsRow)), cardLp())
        if (found.events.isNotEmpty()) results.addView(card(R.string.search_calendar, found.events.map(::eventRow)), cardLp())
        val files = found.files
        if (files.isNotEmpty()) {
            val visible = if (allFiles) files else files.take(Files.SHOWN)
            val rows = visible.map(::fileRow) +
                listOfNotNull(if (visible.size < files.size) showMoreRow(files.size - visible.size) { allFiles = true } else null)
            results.addView(card(R.string.search_files, rows), cardLp())
        }
        if (found.hidePersonal) results.addView(card(null, listOf(unlockRow())), cardLp())
        results.addView(card(null, listOf(webRow(query)) + handoffRows(query)), cardLp())
        resultsScroll.scrollTo(0, 0)
    }

    private fun cardLp() = Ui.lp().apply { bottomMargin = dp(10) }

    /** A rounded card with an optional section title over its rows. */
    private fun card(title: Int?, rows: List<View>): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(pal.card, dp(22).toFloat())
        Ui.clipRounded(this, dp(22).toFloat())
        isClickable = true // Taps between rows don't fall through and close.
        if (title != null) addView(Ui.text(context, 13f, pal.textSecondary, bold = true, lines = 1).apply {
            setText(title)
            setPadding(dp(16), dp(12), dp(16), dp(2))
        })
        rows.forEach { addView(it, Ui.lp()) }
    }

    /** The answer: big result, with what it answers under it. Tapping copies the number. */
    private fun answerCard(a: Answer): View? {
        val (big, small, copy) = when (a) {
            is Answer.Math -> Triple(
                "= " + numbers.format(a.value, Answer.MATH_DIGITS),
                field.text.toString().trim(),
                numbers.plain(a.value, Answer.MATH_DIGITS),
            )
            is Answer.Converted -> a.conversion.let { c ->
                Triple(
                    numbers.format(c.result, Answer.UNIT_DIGITS) + " " + c.to.symbol,
                    numbers.format(c.amount, Answer.UNIT_DIGITS) + " " + c.from.symbol + " =",
                    numbers.plain(c.result, Answer.UNIT_DIGITS),
                )
            }
            is Answer.Time -> {
                val now = java.time.ZonedDateTime.now(a.place.zone)
                val time = android.text.format.DateFormat.getTimeFormat(context).apply {
                    timeZone = java.util.TimeZone.getTimeZone(a.place.zone)
                }.format(java.util.Date())
                val day = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, numbers.locale)
                val here = java.time.ZonedDateTime.now().offset.totalSeconds
                Triple(time, listOf(a.place.name, day, timeDifference(Answer.minutesAhead(here, now.offset.totalSeconds))).joinToString(" · "), time)
            }
            is Answer.Currency -> return currencyCard(a.query)
            is Answer.Clock -> return clockCard(a.command)
        }
        return resultCard(small, big, context.getString(R.string.search_tap_to_copy)) { copy(copy) }
    }

    private fun resultCard(small: String, big: String, note: String, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.pressable(pal.card, dp(22).toFloat(), pal.ripple)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        if (small.isNotEmpty()) addView(Ui.text(context, 13f, pal.textSecondary, lines = 2).apply { text = small })
        addView(Ui.text(context, if (big.length > 24) 20f else 30f, pal.text, bold = true, lines = 3).apply {
            text = big
            setTextIsSelectable(false)
        }, Ui.lp().apply { topMargin = dp(4) })
        addView(Ui.text(context, 12f, pal.textSecondary, lines = 2).apply { text = note },
            Ui.lp().apply { topMargin = dp(6) })
        setOnClickListener { onClick() }
    }

    /**
     * Currency (ADR 0008). Off, the card offers to turn on the daily rates; on, it converts with
     * the saved rates and fetches newer ones in the background once the ECB should have published them.
     */
    private fun currencyCard(q: Money.Query): View? {
        val to = Money.target(q, ExchangeRates.localCurrency(context))
        if (q.from == to) return null
        val amount = numbers.money(q.amount, Money.fractionDigits(q.from)) + " " + q.from + " ="
        if (!Prefs.currencyRates) {
            return resultCard(amount, context.getString(R.string.search_currency_off_title),
                context.getString(R.string.search_currency_off_desc)) { turnOnRates() }
        }
        ExchangeRates.refreshIfStale(::refreshWhenShown)
        val rates = ExchangeRates.rates
        if (rates == null) {
            val state = if (ExchangeRates.failed) R.string.search_currency_failed else R.string.search_currency_loading
            return resultCard(amount, "… $to", context.getString(state)) {}
        }
        val result = rates.convert(q.amount, q.from, to) ?: return null
        val digits = Money.fractionDigits(to)
        val date = rates.date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(numbers.locale))
        return resultCard(amount, numbers.money(result, digits) + " " + to,
            context.getString(R.string.search_currency_note, date)) { copy(numbers.plainMoney(result, digits)) }
    }

    /**
     * Turning on Currency rates changes a setting and starts downloads, so on the lock screen it
     * waits for an unlock, like Settings. Search then shows the rate: in its own window right
     * away, or reopened when the lock-screen view closes on unlock.
     */
    private fun turnOnRates() {
        if (!locked()) {
            Prefs.currencyRates = true
            refresh()
            return
        }
        val query = field.text.toString()
        hideKeyboard()
        host.guarded {
            Prefs.currencyRates = true
            if (host.keepsSearchAfterUnlock) refresh() else SearchActivity.open(host.context, query)
        }
    }

    /**
     * A typed timer or alarm (ADR 0023): what it will set, which only a tap or Go does. Setting it
     * opens nothing, so it works on the lock screen without unlocking.
     */
    private fun clockCard(command: ClockCommand): View = when (command) {
        is ClockCommand.Timer -> resultCard(context.getString(R.string.search_timer), duration(command.seconds),
            context.getString(R.string.search_timer_note)) { setClock(command) }
        is ClockCommand.Alarm -> resultCard(context.getString(R.string.search_alarm), alarmTime(command),
            context.getString(if (ClockActions.isToday(command.hour, command.minute)) R.string.search_alarm_today else R.string.search_alarm_tomorrow)) {
            setClock(command)
        }
    }

    private fun alarmTime(a: ClockCommand.Alarm): String {
        val at = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, a.hour)
            set(java.util.Calendar.MINUTE, a.minute)
        }
        return android.text.format.DateFormat.getTimeFormat(context).format(at.time)
    }

    private fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) context.getString(R.string.search_hours, h) else null,
            if (m > 0) context.getString(R.string.search_minutes, m) else null,
            if (s > 0) context.getString(R.string.search_seconds, s) else null,
        ).joinToString(" ")
    }

    /** Sets [command] in the clock app and closes Search, saying what was set; the Tutorial only says what would be. */
    private fun setClock(command: ClockCommand) {
        hideKeyboard()
        if (app.chronotif.Tutorial.holdsLaunch(context, context.getString(R.string.tutorial_clock))) return
        if (!ClockActions.set(host.context, command)) {
            android.widget.Toast.makeText(context, R.string.search_clock_none, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val said = when (command) {
            is ClockCommand.Timer -> context.getString(R.string.search_timer_started, duration(command.seconds))
            is ClockCommand.Alarm -> context.getString(R.string.search_alarm_set, alarmTime(command))
        }
        android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
        app.chronotif.UsageData.count(Usage.Count.SEARCH_CLOCK)
        close()
    }

    private fun timeDifference(minutes: Int): String {
        if (minutes == 0) return context.getString(R.string.search_time_same)
        val m = Math.abs(minutes)
        val amount = when {
            m % 60 == 0 -> context.getString(R.string.search_hours, m / 60)
            m < 60 -> context.getString(R.string.search_minutes, m)
            else -> context.getString(R.string.search_hours_minutes, m / 60, m % 60)
        }
        return context.getString(if (minutes > 0) R.string.search_time_ahead else R.string.search_time_behind, amount)
    }

    /** Copying a result reveals nothing, so it works on the lock screen too. */
    private fun copy(text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText(text, text))
        // Android 13 shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, R.string.search_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun settingsRow(p: SettingsPages.Page): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(pal.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(Ui.text(context, 16f, pal.text, lines = 1).apply { setText(p.label) }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { launch(Usage.Count.SEARCH_SETTING, context.getString(p.label)) { SettingsPages.open(host.context, p) } }
    }

    /** A row with [icon] in the leading 40dp slot, the way every result row lines up, and an optional second line. */
    private fun iconRow(icon: Int, title: CharSequence, detail: String? = null, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(pal.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, 16f, pal.text, lines = 2).apply { text = title })
            if (detail != null) addView(Ui.text(context, 13f, pal.textSecondary, lines = 1).apply { text = detail })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
    }

    /** Photo (or initial), name and the number the buttons use; tapping opens the contact. */
    private fun contactRow(c: Contact): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(6), dp(8))
        val avatar = FrameLayout(context).apply {
            background = Ui.rounded(pal.chip, dp(20).toFloat())
            addView(Ui.text(context, 17f, pal.textSecondary, bold = true, lines = 1).apply {
                text = c.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
                gravity = Gravity.CENTER
            }, LayoutParams(-1, -1))
            val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(photo, LayoutParams(-1, -1))
            PersonalData.photo(context, c) { photo.setImageDrawable(it) }
            Ui.clipOval(this)
        }
        addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, 16f, pal.text, lines = 1).apply { text = c.name })
            val detail = listOfNotNull(c.callNumber, if (c.whatsapp != null) context.getString(R.string.search_whatsapp) else null)
            if (detail.isNotEmpty()) addView(Ui.text(context, 13f, pal.textSecondary, lines = 1).apply { text = detail.joinToString(" · ") })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (c.callNumber != null || c.whatsapp?.chat != null) {
            addView(actionButton(R.drawable.ic_message, context.getString(R.string.search_message, c.name)) {
                PersonalData.message(host.context, c)
            })
        }
        if (PersonalData.canVideoCall(context, c)) {
            addView(actionButton(R.drawable.ic_video, context.getString(R.string.search_video_call, c.name)) {
                PersonalData.videoCall(host.context, c)
            })
        }
        if (c.callNumber != null || c.whatsapp?.call != null) {
            addView(actionButton(R.drawable.ic_call, context.getString(R.string.search_call, c.name)) {
                PersonalData.call(host.context, c)
            })
        }
        setOnClickListener { launch(Usage.Count.SEARCH_CONTACT, contactApp) { PersonalData.openContact(host.context, c) } }
    }

    private fun actionButton(icon: Int, label: String, action: () -> Unit): View = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(pal.accent)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(0, dp(22).toFloat(), pal.ripple)
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { launch(Usage.Count.SEARCH_CONTACT, contactApp, action) }
    }

    /** "Show more" under a card: [more] is how many aren't showing; [expand] lets more show. */
    private fun showMoreRow(more: Int, expand: () -> Unit): View = Ui.text(context, 15f, pal.accent, lines = 1).apply {
        text = context.getString(R.string.search_show_more, more)
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(68), dp(12), dp(14), dp(14))
        setOnClickListener {
            expand()
            val y = resultsScroll.scrollY
            shown?.let(::show)
            resultsScroll.post { resultsScroll.scrollTo(0, y) }
        }
    }

    /** A typed number that no contact has: call, message, and WhatsApp when it's installed. */
    private fun numberRow(number: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(6), dp(8))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_call)
            imageTintList = ColorStateList.valueOf(pal.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val shown = android.telephony.PhoneNumberUtils.formatNumber(number, numbers.locale.country.ifEmpty { "US" }) ?: number
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, 16f, pal.text, lines = 1).apply { text = shown })
            addView(Ui.text(context, 13f, pal.textSecondary, lines = 1).apply { setText(R.string.search_number) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val whatsApp = PersonalData.whatsAppPackages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.mine && it.component.packageName == pkg } }
        if (whatsApp != null) {
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = Ui.pressable(0, dp(22).toFloat(), pal.ripple)
                contentDescription = context.getString(R.string.search_message_on, shown, whatsApp.label)
                Launchables.icon(context, whatsApp) { setImageDrawable(RoundedIcon(it)) }
                setOnClickListener {
                    launchIf(Usage.Count.SEARCH_NUMBER, whatsApp.label) {
                        PersonalData.whatsAppNumber(host.context, number, whatsApp.component.packageName)
                    }
                }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(numberButton(R.drawable.ic_message, context.getString(R.string.search_message, shown)) {
            PersonalData.messageNumber(host.context, number)
        })
        addView(numberButton(R.drawable.ic_call, context.getString(R.string.search_call, shown)) {
            PersonalData.callNumber(host.context, number)
        })
        setOnClickListener { launchIf(Usage.Count.SEARCH_NUMBER, contactApp) { PersonalData.callNumber(host.context, number) } }
    }

    private fun numberButton(icon: Int, label: String, action: () -> Boolean): View = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(pal.accent)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(0, dp(22).toFloat(), pal.ripple)
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { launchIf(Usage.Count.SEARCH_NUMBER, contactApp, action) }
    }

    /**
     * A file: a thumbnail for photos, videos and songs, filled in once loaded, else its kind's icon;
     * a song's artist and album, or the folder, and when it last changed.
     */
    private fun fileRow(f: FoundFile): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val icon = ImageView(context).apply {
            setImageResource(when (f.kind) {
                FoundFile.Kind.IMAGE -> R.drawable.ic_image
                FoundFile.Kind.VIDEO -> R.drawable.ic_movie
                FoundFile.Kind.AUDIO -> R.drawable.ic_music
                FoundFile.Kind.DOCUMENT -> R.drawable.ic_file
            })
            imageTintList = ColorStateList.valueOf(pal.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.rounded(pal.chip, dp(10).toFloat())
            Ui.clipRounded(this, dp(10).toFloat())
        }
        Files.thumbnail(context, f, dp(40)) {
            icon.imageTintList = null
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setImageDrawable(it)
        }
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val music = if (f.kind == FoundFile.Kind.AUDIO) listOfNotNull(f.artist, f.album).joinToString(" · ").ifEmpty { null } else null
        val changed = android.text.format.DateUtils.formatDateTime(context, f.modified * 1000,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_ALL or
                (if (sameYear(f.modified * 1000)) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(context, 16f, pal.text, lines = 1).apply {
                text = if (f.kind == FoundFile.Kind.AUDIO) f.title ?: f.name else f.name
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(Ui.text(context, 13f, pal.textSecondary, lines = 1).apply {
                text = listOfNotNull(music ?: f.folder, changed).joinToString(" · ")
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener {
            launchIf(Usage.Count.SEARCH_FILE, f.name) {
                Files.open(host.context, f).also { opened ->
                    if (!opened) android.widget.Toast.makeText(context, R.string.search_file_no_app, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Under the web row: the same query in the Play Store, Maps, YouTube, Spotify and YouTube Music, for those installed. */
    private fun handoffRows(query: String): List<View> = Handoff.entries.mapNotNull { h ->
        val app = apps.firstOrNull { it.mine && it.component.packageName == h.pkg } ?: return@mapNotNull null
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pressable(0, 0f, pal.ripple)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val icon = ImageView(context)
            Launchables.icon(context, app) { icon.setImageDrawable(RoundedIcon(it)) }
            addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(Ui.text(context, 16f, pal.text, lines = 1).apply { text = context.getString(R.string.search_in_app, app.label) },
                LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { launchIf(Usage.Count.SEARCH_HANDOFF, app.label) { h.open(host.context, query) } }
        }
    }

    private fun eventRow(e: CalendarEvent): View {
        val flags = android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
            android.text.format.DateUtils.FORMAT_ABBREV_ALL or
            (if (e.allDay) 0 else android.text.format.DateUtils.FORMAT_SHOW_TIME) or
            (if (sameYear(e.begin)) 0 else android.text.format.DateUtils.FORMAT_SHOW_YEAR)
        // All-day events are stored at midnight UTC.
        val time = android.text.format.DateUtils.formatDateRange(
            context, java.util.Formatter(StringBuilder(), numbers.locale), e.begin, e.end, flags,
            if (e.allDay) "UTC" else null,
        ).toString()
        return iconRow(R.drawable.ic_event, e.title, time) {
            launch(Usage.Count.SEARCH_CALENDAR, context.getString(R.string.tutorial_calendar)) { PersonalData.openEvent(host.context, e) }
        }
    }

    private fun sameYear(t: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(t).atZone(zone).year == java.time.LocalDate.now(zone).year
    }

    /**
     * Shown instead of contacts, events and Files while the lock screen hides content. After unlocking, Search shows everything:
     * in its own window right away, or reopened when the lock-screen view closes on unlock.
     */
    private fun unlockRow(): View = iconRow(R.drawable.ic_lock, context.getString(R.string.search_unlock_for_more)) {
        val query = field.text.toString()
        hideKeyboard()
        host.guarded {
            if (host.keepsSearchAfterUnlock) {
                refresh()
                focusField()
            } else {
                SearchActivity.open(host.context, query)
            }
        }
    }

    private val contactApp get() = context.getString(R.string.tutorial_contacts_app)

    /**
     * Everything Search opens goes through here. In the Search part of the Tutorial nothing opens:
     * Search says what would have ([what]) instead. [kind] is what Usage data counts: which sort of
     * result was opened, never which one.
     */
    private fun launch(kind: Usage.Count, what: CharSequence, action: () -> Unit) = launchIf(kind, what) { action(); true }

    /** As [launch], for an [action] that may find nothing to open; then nothing is counted and Search doesn't close for it. */
    private fun launchIf(kind: Usage.Count, what: CharSequence, action: () -> Boolean) {
        hideKeyboard()
        if (app.chronotif.Tutorial.holdsLaunch(context, what)) return
        host.guarded {
            if (!action()) return@guarded
            app.chronotif.UsageData.count(kind)
            host.launched()
        }
    }

    /** The web row, over the hand-off rows: an address opens directly, anything else is searched with the chosen engine. */
    private fun webRow(query: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(pal.textSecondary)
            scaleType = ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val label = if (WebQuery.isAddress(query)) context.getString(R.string.search_open_address, query.trim())
        else context.getString(R.string.search_web, query.trim())
        addView(Ui.text(context, 16f, pal.text, lines = 2).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { openWeb(query) }
    }

    private fun openWeb(query: String) {
        val engine = WebQuery.Engine.entries.getOrElse(Prefs.searchEngine) { WebQuery.Engine.GOOGLE }
        val url = if (WebQuery.isAddress(query)) WebQuery.address(query) else WebQuery.search(engine, query)
        launch(Usage.Count.SEARCH_WEB, context.getString(R.string.tutorial_browser)) {
            runCatching {
                host.context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** An icon with its name under it, for the suggestion row. */
    private fun cell(a: Launchable): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = Ui.pressable(0, dp(14).toFloat(), pal.ripple)
        setPadding(dp(2), dp(4), dp(2), dp(4))
        val icon = ImageView(context)
        Launchables.icon(context, a) { icon.setImageDrawable(RoundedIcon(it)) }
        addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(Ui.text(context, 12f, pal.text, lines = 1).apply {
            text = a.label
            gravity = Gravity.CENTER_HORIZONTAL
        }, Ui.lp().apply { topMargin = dp(6) })
        contentDescription = a.label
        setOnClickListener { open(a) }
    }

    private fun row(a: Launchable): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val icon = ImageView(context)
        Launchables.icon(context, a) { icon.setImageDrawable(RoundedIcon(it)) }
        addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        addView(Ui.text(context, 16f, pal.text, lines = 1).apply { text = a.label }, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { open(a) }
    }

    private fun open(a: Launchable) = launchIf(Usage.Count.SEARCH_APP, a.label) {
        Launchables.launch(host.context, a).also { opened ->
            if (!opened) {
                android.widget.Toast.makeText(context, R.string.search_app_gone, android.widget.Toast.LENGTH_SHORT).show()
                loadApps()
            }
        }
    }

    private fun focusField() {
        field.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun close() {
        if (closing) return
        closing = true
        hideKeyboard()
        animate().alpha(0f).setDuration(150).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onClosed()
        }.start()
    }

    /** What sits behind in the same window, blurred while Search is up. */
    private val blurred = ArrayList<View>()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val parent = parent as? ViewGroup
        if (Build.VERSION.SDK_INT >= 31 && parent != null) {
            val blur = android.graphics.RenderEffect.createBlurEffect(
                dp(24).toFloat(), dp(24).toFloat(), android.graphics.Shader.TileMode.CLAMP,
            )
            for (i in 0 until parent.childCount) {
                val v = parent.getChildAt(i)
                if (v === this || v is Overlay) continue
                v.setRenderEffect(blur)
                blurred += v
            }
        }
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
        requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back!!)
        }
        // The keyboard only comes up once the field is in a focused window.
        post(::focusField)
        if (stale) {
            stale = false
            refresh()
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) blurred.forEach { it.setRenderEffect(null) }
        blurred.clear()
        if (Build.VERSION.SDK_INT >= 33) findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back!!)
        super.onDetachedFromWindow()
    }

    // --- Swipe up (once the results can't scroll further) closes it.

    private var downY = 0f
    private var atBottomOnDown = false
    private var tracker: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                atBottomOnDown = resultsScroll.visibility != VISIBLE || !resultsScroll.canScrollVertically(1)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> tracker?.addMovement(ev)
            MotionEvent.ACTION_UP -> {
                val t = tracker
                t?.addMovement(ev)
                t?.computeCurrentVelocity(1000)
                val vy = t?.yVelocity ?: 0f
                t?.recycle()
                tracker = null
                if (atBottomOnDown && downY - ev.rawY > dp(90) && vy < -dp(500)) {
                    MotionEvent.obtain(ev).also {
                        it.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(it)
                        it.recycle()
                    }
                    close()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
