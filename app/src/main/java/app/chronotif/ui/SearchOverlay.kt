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
import app.chronotif.Launchables
import app.chronotif.Launchable
import app.chronotif.PersonalData
import app.chronotif.R
import app.chronotif.Prefs
import app.chronotif.SettingsPages
import app.chronotif.core.Answer
import app.chronotif.core.AppMatch
import app.chronotif.core.Numbers
import app.chronotif.core.PersonalMatch
import app.chronotif.core.WebQuery

/**
 * Search (ADR 0008), laid over the panel, the lock-screen view or its own window. Before typing:
 * the apps that notified most recently. Typing: an answer (math, units, time in a place), apps,
 * contacts, Settings pages, calendar events and, last, the web. It never searches notifications.
 * Opening anything goes through [NotifList.Host.guarded], so on the lock screen it unlocks first;
 * copying an answer doesn't. Contacts and events stay hidden while the phone is locked.
 */
@SuppressLint("ViewConstructor")
class SearchOverlay private constructor(
    context: Context,
    private val host: NotifList.Host,
    initialQuery: String,
    private val onClosed: () -> Unit,
) : FrameLayout(context), Overlay {

    companion object {
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
    /** Answer, apps, contacts, Settings pages, events and the web row, each its own card, in that order. */
    private val results = LinearLayout(context)
    private val numbers = Numbers(context.resources.configuration.locales[0])
    private val spacer = View(context)
    private val field = EditText(context)
    private val fieldBox = LinearLayout(context)
    private var apps: List<Launchable> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private var events: List<CalendarEvent> = emptyList()
    /** "Show more" was tapped for this query's contacts. */
    private var allContacts = false
    private var shownQuery = ""
    private var closing = false

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
            // Go opens the top app, or goes to the web when no app matches.
            setOnEditorActionListener { _, id, _ ->
                if (id != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
                val q = text.toString()
                if (AppMatch.fold(q).isNotEmpty()) {
                    AppMatch.search(apps, q) { it.label }.firstOrNull()?.let(::open) ?: openWeb(q)
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
        Launchables.load(context) { list ->
            apps = list
            refresh()
        }
        PersonalData.contacts(context) { list ->
            contacts = list
            if (list.isNotEmpty()) refresh()
        }
        PersonalData.events(context) { list ->
            events = list
            if (list.isNotEmpty()) refresh()
        }
    }

    private fun locked() = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private fun refresh() {
        if (closing) return
        val query = field.text.toString()
        if (AppMatch.fold(query).isEmpty()) {
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
        suggestionsBlock.visibility = GONE
        resultsScroll.visibility = VISIBLE
        spacer.visibility = GONE
        if (query != shownQuery) allContacts = false
        shownQuery = query
        results.removeAllViews()
        Answer.of(query, numbers)?.let { results.addView(answerCard(it), cardLp()) }
        val found = AppMatch.search(apps, query) { it.label }
        if (found.isNotEmpty()) results.addView(card(R.string.search_apps, found.map(::row)), cardLp())
        // Who you know and where you'll be stay private until the phone is unlocked.
        val hidePersonal = locked() && (PersonalData.contactsOn(context) || PersonalData.calendarOn(context))
        val people = if (hidePersonal) emptyList() else PersonalMatch.contacts(contacts, query, { it.name }, { it.numbers })
        if (people.isNotEmpty()) {
            val shown = if (allContacts) people.take(PersonalMatch.CONTACTS_MAX) else people.take(PersonalMatch.CONTACTS_SHOWN)
            val rows = shown.map(::contactRow) +
                listOfNotNull(if (shown.size < people.size && !allContacts) showMoreRow(people.size - shown.size) else null)
            results.addView(card(R.string.search_contacts, rows), cardLp())
        }
        val pages = SettingsPages.search(context, query)
        if (pages.isNotEmpty()) results.addView(card(R.string.search_settings, pages.map(::settingsRow)), cardLp())
        val upcoming = if (hidePersonal) emptyList()
        else PersonalMatch.events(events, query, System.currentTimeMillis(), { it.id }, { it.title }, { it.begin }, { it.end })
        if (upcoming.isNotEmpty()) results.addView(card(R.string.search_calendar, upcoming.map(::eventRow)), cardLp())
        if (hidePersonal) results.addView(card(null, listOf(unlockRow())), cardLp())
        results.addView(card(null, listOf(webRow(query))), cardLp())
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
    private fun answerCard(a: Answer): View {
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
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.pressable(pal.card, dp(22).toFloat(), pal.ripple)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(Ui.text(context, 13f, pal.textSecondary, lines = 2).apply { text = small })
            addView(Ui.text(context, 30f, pal.text, bold = true, lines = 2).apply {
                text = big
                setTextIsSelectable(false)
            }, Ui.lp().apply { topMargin = dp(4) })
            addView(Ui.text(context, 12f, pal.textSecondary, lines = 1).apply { setText(R.string.search_tap_to_copy) },
                Ui.lp().apply { topMargin = dp(6) })
            setOnClickListener { copy(copy) }
        }
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
        setOnClickListener {
            hideKeyboard()
            host.guarded {
                SettingsPages.open(host.context, p)
                host.launched()
            }
        }
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
        if (c.callNumber != null || c.whatsapp?.call != null) {
            addView(actionButton(R.drawable.ic_call, context.getString(R.string.search_call, c.name)) {
                PersonalData.call(host.context, c)
            })
        }
        setOnClickListener { launch { PersonalData.openContact(host.context, c) } }
    }

    private fun actionButton(icon: Int, label: String, action: () -> Unit): View = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(pal.accent)
        scaleType = ImageView.ScaleType.CENTER
        background = Ui.pressable(0, dp(22).toFloat(), pal.ripple)
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { launch(action) }
    }

    private fun showMoreRow(more: Int): View = Ui.text(context, 15f, pal.accent, lines = 1).apply {
        text = context.getString(R.string.search_show_more, more)
        background = Ui.pressable(0, 0f, pal.ripple)
        setPadding(dp(68), dp(12), dp(14), dp(14))
        setOnClickListener {
            allContacts = true
            refresh()
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
        return iconRow(R.drawable.ic_event, e.title, time) { launch { PersonalData.openEvent(host.context, e) } }
    }

    private fun sameYear(t: Long): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(t).atZone(zone).year == java.time.LocalDate.now(zone).year
    }

    /**
     * Shown instead of contacts and events while locked. After unlocking, Search shows everything:
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

    private fun launch(action: () -> Unit) {
        hideKeyboard()
        host.guarded {
            action()
            host.launched()
        }
    }

    /** The last row: an address opens directly, anything else is searched with the chosen engine. */
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
        hideKeyboard()
        host.guarded {
            runCatching {
                host.context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            host.launched()
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

    private fun open(a: Launchable) {
        hideKeyboard()
        host.guarded {
            Launchables.launch(host.context, a)
            host.launched()
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
