// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.chronotif.TutorialSource.Event
import app.chronotif.core.HandleAction
import app.chronotif.core.HandleGesture
import app.chronotif.ui.Panel

/**
 * A hands-on run through Chronotif in the real Panel, with [Samples] in place of the phone's
 * notifications (see GLOSSARY). A part is a few steps; each asks the user to try one thing,
 * shows when it worked, and moves on with Continue, whether or not they tried.
 *
 * A run lives only in memory: it ends once the tutorial screen, the Panel and Search are all
 * gone, or Android stops Chronotif, and the real Panel is back at once. Only the step reached
 * is saved, so the part continues there next time.
 */
object Tutorial {

    enum class Part(val title: Int, val desc: Int) {
        NOTIFICATIONS(R.string.tutorial_notifications, R.string.tutorial_notifications_desc),
        SEARCH(R.string.tutorial_search, R.string.tutorial_search_desc),
        LOCK_SCREEN(R.string.tutorial_lock, R.string.tutorial_lock_desc),
    }

    /**
     * One step: what to try, and the moment it counts as tried, by an [Event] or, for what the
     * user does outside Chronotif (placing a widget), by how things stand when they're back.
     */
    class Step(
        val title: Int,
        val body: (Context) -> CharSequence,
        val tried: (Run, Event) -> Boolean = { _, _ -> false },
        val triedNow: ((Context) -> Boolean)? = null,
        val extras: List<Extra> = emptyList(),
    )

    /**
     * A button the tutorial screen offers with a step, such as adding a tile. Where Android or the
     * home screen can't do it, [manual] says how to do it by hand instead.
     */
    class Extra(
        val label: Int,
        val manual: Int?,
        val available: (Context) -> Boolean,
        /** A choice among extras: whether this one is the current pick, shown with a check. */
        val picked: ((Context) -> Boolean)? = null,
        val act: (Context) -> Unit,
    )

    class Run internal constructor(val part: Part, val startedAt: Long, val source: TutorialSource) {
        var step = 0
            internal set
        /** The current step was tried. */
        var tried = false
            internal set
        var finished = false
            internal set
        internal var checked = false
        internal var stackOpened = false
        internal var mayaWroteAgain = false
        val steps get() = steps(part)
    }

    var run: Run? = null
        private set

    /** The Tutorial's samples while a part runs, for the Panel to show instead of the phone's. */
    val source: CardSource? get() = run?.source

    /** Where a run is being followed. Once none of them is up, the run ends. */
    enum class Surface { SCREEN, PANEL, SEARCH, LOCK }

    private val shown = HashSet<Surface>()

    /** The tutorial screen, the Panel or Search (from the edge handle) came up, or went. */
    fun isShown(surface: Surface) = surface in shown

    fun shown(surface: Surface, on: Boolean) {
        if (on) shown += surface else shown -= surface
        if (shown.isEmpty()) pause()
    }

    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun changed() = listeners.toList().forEach { it() }

    fun steps(part: Part): List<Step> = when (part) {
        Part.NOTIFICATIONS -> notificationSteps
        Part.SEARCH -> searchSteps
        Part.LOCK_SCREEN -> lockSteps
    }

    /** The part after [part], offered once it's finished. */
    fun nextPart(part: Part): Part? = Part.entries.getOrNull(part.ordinal + 1)

    /** Something the user did outside the Panel's cards: in Search, or back from the home screen. */
    fun notice(e: Event) {
        run?.let { onEvent(it, e) }
    }

    /** Back on the tutorial screen: a step done outside Chronotif, such as placing a widget, counts now. */
    fun recheck(context: Context) {
        val r = run ?: return
        if (r.finished || r.tried) return
        if (r.steps[r.step].triedNow?.invoke(context) == true) {
            r.tried = true
            changed()
        }
    }

    /**
     * While the Search part runs, Search says what a tap would open instead of opening it, so the
     * user stays in the tutorial. True when it did.
     */
    fun holdsLaunch(context: Context, what: CharSequence): Boolean {
        val r = run ?: return false
        if (r.part != Part.SEARCH || r.finished) return false
        android.widget.Toast.makeText(context, context.getString(R.string.tutorial_would_open, what), android.widget.Toast.LENGTH_SHORT).show()
        onEvent(r, Event.APP_TAPPED)
        return true
    }

    /** The step a part was left at, or null when it hasn't been started or was finished. */
    fun placeOf(part: Part): Int? = Prefs.tutorialPlace?.split(':')
        ?.takeIf { it.size == 2 && it[0] == part.name }?.get(1)?.toIntOrNull()
        ?.takeIf { it in steps(part).indices }

    fun finished(part: Part) = part.name in Prefs.tutorialDone

    /** Starts [part], at the step it was left at, with fresh samples. The Panel opens with them from now on. */
    fun start(context: Context, part: Part, fromStart: Boolean = false): Run {
        run?.takeIf { it.part == part && !it.finished && !fromStart }?.let { return it }
        main.removeCallbacksAndMessages(null)
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // Moving on to the next part, perhaps with the Panel still open, keeps the same samples.
        val previous = run?.takeIf { it.part != part && !fromStart }
        lateinit var r: Run
        r = if (previous != null) {
            Run(part, previous.startedAt, previous.source).also { previous.source.onEvent = { e -> onEvent(r, e) } }
        } else {
            Run(part, now, samples(app, part, now) { onEvent(r, it) })
        }
        r.step = if (fromStart) 0 else placeOf(part) ?: 0
        run = r
        Panel.drop()
        enter(app, r)
        changed()
        return r
    }

    /** Ends the run and brings the real Panel back; the step reached stays saved. */
    fun pause() {
        if (run == null) return
        main.removeCallbacksAndMessages(null)
        run = null
        shown -= Surface.PANEL
        Panel.drop()
        changed()
    }

    fun next(context: Context) {
        val r = run ?: return
        if (r.finished) return
        if (r.step == r.steps.lastIndex) {
            r.finished = true
            Prefs.tutorialDone = Prefs.tutorialDone + r.part.name
            UsageData.count(app.chronotif.core.Usage.Count.TUTORIAL_PART_DONE)
            Prefs.tutorialPlace = null
        } else {
            r.step++
            enter(context.applicationContext, r)
        }
        changed()
    }

    fun back(context: Context) {
        val r = run ?: return
        if (r.finished || r.step == 0) return
        r.step--
        enter(context.applicationContext, r)
        changed()
    }

    private fun enter(app: Context, r: Run) {
        r.tried = false
        r.checked = false
        r.stackOpened = false
        Prefs.tutorialPlace = "${r.part.name}:${r.step}"
        if (r.part == Part.NOTIFICATIONS && r.step == NEW_AND_EARLIER && !r.mayaWroteAgain) {
            // A new message arrives while the user watches the Panel.
            main.postDelayed({
                if (run !== r) return@postDelayed
                r.mayaWroteAgain = true
                r.source.post(maya(app, r.startedAt, writesAgainAt = System.currentTimeMillis()))
            }, NEW_MESSAGE_DELAY_MS)
        }
    }

    private fun onEvent(r: Run, e: Event) {
        when (e) {
            Event.PANEL_OPENED -> shown += Surface.PANEL
            Event.PANEL_CLOSED -> shown -= Surface.PANEL
            Event.CHECKED -> r.checked = true
            Event.STACK_OPENED -> r.stackOpened = true
            else -> {}
        }
        if (run !== r) return
        if (!r.finished && !r.tried && r.steps[r.step].tried(r, e)) r.tried = true
        changed()
        if (e == Event.PANEL_CLOSED && shown.isEmpty()) pause()
    }

    // --- Notifications

    private const val NEW_AND_EARLIER = 1
    private const val NEW_MESSAGE_DELAY_MS = 2500L

    /** Asking Android to add a tile needs Android 13 and Chronotif in front. */
    private val canAddTile: (Context) -> Boolean = { android.os.Build.VERSION.SDK_INT >= 33 }

    private val panelTile = Extra(R.string.tutorial_add_tile, R.string.tutorial_add_tile_manual, canAddTile) { ctx ->
        if (android.os.Build.VERSION.SDK_INT < 33) return@Extra
        ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            android.content.ComponentName(ctx, PanelTile::class.java),
            ctx.getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_tile),
            ctx.mainExecutor,
        ) {}
    }

    /** The first edge-handle gesture set to [action], which a step names, or null when none is. */
    private fun handleGesture(action: HandleAction): HandleGesture? =
        HandleGesture.entries.firstOrNull { Prefs.handleAction(it) == action }

    /** "Tap the edge handle" and the like, for a step's text. */
    private fun doing(g: HandleGesture) = when (g) {
        HandleGesture.TAP -> R.string.handle_do_tap
        HandleGesture.DOUBLE_TAP -> R.string.handle_do_double_tap
        HandleGesture.HOLD -> R.string.handle_do_hold
        HandleGesture.SWIPE_DOWN -> R.string.handle_do_swipe_down
        HandleGesture.SWIPE_UP -> R.string.handle_do_swipe_up
        HandleGesture.SWIPE_INWARD -> R.string.handle_do_swipe_inward
    }

    /** Settings → Edge handle, offered by a step while no gesture does its [action]. */
    private fun gestures(action: HandleAction) = Extra(R.string.tutorial_gestures, null, { handleGesture(action) == null }) { ctx ->
        Prefs.settingsSection = "HANDLE"
        ctx.startActivity(android.content.Intent(ctx, app.chronotif.ui.SetupActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val notificationSteps = listOf(
        Step(R.string.tutorial_open_title, { ctx ->
            val swipe = Prefs.statusSwipeEnabled && StatusBarSwipe.isEnabled(ctx)
            val gesture = handleGesture(HandleAction.PANEL)
            when {
                gesture == null -> ctx.getString(if (swipe) R.string.tutorial_open_none_swipe else R.string.tutorial_open_none)
                else -> ctx.getString(if (swipe) R.string.tutorial_open_body_swipe else R.string.tutorial_open_body, ctx.getString(doing(gesture)))
            }
        }, { _, e -> e == Event.PANEL_OPENED }, extras = listOf(panelTile, gestures(HandleAction.PANEL))),
        Step(R.string.tutorial_check_title, { it.getString(R.string.tutorial_check_body) },
            { r, e -> e == Event.PANEL_OPENED && r.checked }),
        Step(R.string.tutorial_stack_title, { it.getString(R.string.tutorial_stack_body) },
            { r, e -> e == Event.STACK_CLOSED && r.stackOpened }),
        Step(R.string.tutorial_preview_title, { it.getString(R.string.tutorial_preview_body) },
            { _, e -> e == Event.REPLIED || e == Event.SNOOZED }),
        Step(R.string.tutorial_clear_title, { it.getString(R.string.tutorial_clear_body) },
            { _, e -> e == Event.CLEARED }),
        Step(R.string.tutorial_close_title, { it.getString(R.string.tutorial_close_body) },
            { _, e -> e == Event.PANEL_CLOSED }),
    )

    // --- Search

    private val searchSteps = listOf(
        Step(R.string.tutorial_pull_title, { it.getString(R.string.tutorial_pull_body) }, { _, e -> e == Event.SEARCH }),
        Step(R.string.tutorial_hold_title, { ctx ->
            handleGesture(HandleAction.SEARCH)?.let { ctx.getString(R.string.tutorial_hold_body, ctx.getString(doing(it))) }
                ?: ctx.getString(R.string.tutorial_hold_none)
        }, { _, e -> e == Event.SEARCH_ELSEWHERE }, extras = listOf(gestures(HandleAction.SEARCH))),
        Step(R.string.tutorial_answer_title, { it.getString(R.string.tutorial_answer_body) }, { _, e -> e == Event.ANSWER_SHOWN }),
        Step(R.string.tutorial_app_title, { it.getString(R.string.tutorial_app_body) }, { _, e -> e == Event.APP_TAPPED }),
        Step(R.string.tutorial_home_title, { it.getString(R.string.tutorial_home_body) },
            triedNow = SearchSetup::widgetPlaced,
            extras = listOf(
                Extra(R.string.search_setup_widget, R.string.search_setup_widget_manual, SearchSetup::canPinWidget, act = SearchSetup::pinWidget),
                Extra(R.string.search_setup_tile, R.string.search_setup_tile_manual, canAddTile) { SearchSetup.addTile(it) },
            )),
        // The Search checklist's steps, as they stand; they're done in Settings, the same as there.
        Step(R.string.tutorial_more_title, { ctx ->
            val lines = SearchSetup.counted.filter { it != SearchSetup.Step.WIDGET }.joinToString("\n") {
                (if (SearchSetup.done(ctx, it)) "✓  " else "○  ") + ctx.getString(SearchSetup.title(it))
            }
            ctx.getString(R.string.tutorial_more_body) + "\n\n" + lines
        }, triedNow = { ctx -> SearchSetup.left(ctx) == 0 },
            extras = listOf(Extra(R.string.tutorial_more_open, null, { true }) { ctx ->
                Prefs.settingsSection = "SEARCH"
                ctx.startActivity(android.content.Intent(ctx, app.chronotif.ui.SetupActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })),
    )

    // --- Lock screen, on a stand-in of the lock-screen view while the phone is unlocked.

    private val showLock = Extra(R.string.tutorial_show_lock, null, { !isShown(Surface.LOCK) }) { ctx ->
        ctx.startActivity(app.chronotif.ui.LockActivity.practice(ctx))
    }

    private fun lockMode(label: Int, mode: Int) = Extra(label, null, { true }, picked = { Prefs.lockMode == mode }) {
        Prefs.lockMode = mode
        notice(Event.LOCK_MODE_PICKED)
    }

    private val lockSteps = listOf(
        Step(R.string.tutorial_pile_title, { it.getString(R.string.tutorial_pile_body) },
            { _, e -> e == Event.PILE_FOLDED }, extras = listOf(showLock)),
        Step(R.string.tutorial_pile_tap_title, { it.getString(R.string.tutorial_pile_tap_body) },
            { _, e -> e == Event.PILE_TAPPED }, extras = listOf(showLock)),
        Step(R.string.tutorial_lock_reply_title, { it.getString(R.string.tutorial_lock_reply_body) },
            { _, e -> e == Event.REPLIED }, extras = listOf(showLock)),
        Step(R.string.tutorial_lock_clock_title, { it.getString(R.string.tutorial_lock_clock_body) },
            { _, e -> e == Event.UNLOCK_ASKED }, extras = listOf(showLock)),
        Step(R.string.tutorial_lock_search_title, { it.getString(R.string.tutorial_lock_search_body) },
            { _, e -> e == Event.SEARCH }, extras = listOf(showLock)),
        Step(R.string.tutorial_sleep_title, { ctx ->
            ctx.getString(R.string.tutorial_sleep_body) +
                (if (Prefs.lockDoubleTapSleep) "" else "\n\n" + ctx.getString(R.string.tutorial_sleep_off))
        }, { _, e -> e == Event.SLEPT }, extras = listOf(showLock)),
        Step(R.string.tutorial_shortcut_title, { ctx ->
            ctx.getString(R.string.tutorial_shortcut_body) +
                (if (Prefs.lockShortcutLeft != app.chronotif.core.LockShortcut.NONE ||
                    Prefs.lockShortcutRight != app.chronotif.core.LockShortcut.NONE) "" else "\n\n" + ctx.getString(R.string.tutorial_shortcut_none)) +
                (if (Haptics.on(ctx)) "" else "\n\n" + ctx.getString(R.string.lock_shortcut_haptics_off_desc))
        }, { _, e -> e == Event.SHORTCUT_HELD }, extras = listOf(showLock)),
        Step(R.string.tutorial_lock_mode_title, { it.getString(R.string.tutorial_lock_mode_body) },
            { _, e -> e == Event.LOCK_MODE_PICKED },
            extras = listOf(
                lockMode(R.string.lock_off, Prefs.LOCK_OFF),
                lockMode(R.string.lock_new, Prefs.LOCK_WHEN_NEW),
                lockMode(R.string.lock_any, Prefs.LOCK_ALWAYS),
            )),
    )

    private const val MINUTE = 60_000L

    /** Every part shows the same samples: Search opens over them, and the lock-screen stand-in rests them as its Pile. */
    private fun samples(app: Context, part: Part, now: Long, onEvent: (Event) -> Unit): TutorialSource {
        return when (part) {
            Part.NOTIFICATIONS, Part.SEARCH, Part.LOCK_SCREEN -> TutorialSource(listOf(
                // Checked already: Earlier.
                Samples.entry(app, Samples.stride, 1, now - 180 * MINUTE) {
                    setContentTitle(app.getString(R.string.sample_stride_title)).setContentText(app.getString(R.string.sample_stride_text))
                },
                Samples.entry(app, Samples.fernbank, 2, now - 120 * MINUTE) {
                    setContentTitle(app.getString(R.string.sample_fernbank_title)).setContentText(app.getString(R.string.sample_fernbank_text))
                },
                // Since the last check: New.
                Samples.entry(app, Samples.daybook, 3, now - 40 * MINUTE) {
                    setContentTitle(app.getString(R.string.sample_daybook_title)).setContentText(app.getString(R.string.sample_daybook_text))
                },
                Samples.entry(app, Samples.parcelly, 4, now - 25 * MINUTE) {
                    setContentTitle(app.getString(R.string.sample_parcelly_title)).setContentText(app.getString(R.string.sample_parcelly_text))
                },
                maya(app, now),
            ), lastCheck = now - 90 * MINUTE, onEvent = onEvent)
        }
    }

    /**
     * Maya Chen's chat on Murmur: two messages from before the part started, and a third at
     * [writesAgainAt] once she writes again. The first two keep their times, so a check still covers them.
     */
    private fun maya(app: Context, startedAt: Long, writesAgainAt: Long? = null): Entry {
        val maya = Samples.person(app.getString(R.string.sample_maya), 0xFFD9435E.toInt())
        return Samples.entry(app, Samples.murmur, 5, writesAgainAt ?: (startedAt - 10 * MINUTE)) {
            val style = Notification.MessagingStyle(Samples.me)
                .addMessage(app.getString(R.string.sample_maya_1), startedAt - 12 * MINUTE, maya)
                .addMessage(app.getString(R.string.sample_maya_2), startedAt - 10 * MINUTE, maya)
            if (writesAgainAt != null) style.addMessage(app.getString(R.string.sample_maya_3), writesAgainAt, maya)
            setStyle(style)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .addAction(Samples.replyButton(app.getString(R.string.sample_reply)))
        }
    }
}
