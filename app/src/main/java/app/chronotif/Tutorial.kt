// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.chronotif.TutorialSource.Event
import app.chronotif.ui.Panel

/**
 * A hands-on run through Chronotif in the real Panel, with [Samples] in place of the phone's
 * notifications (see GLOSSARY). A part is a few steps; each asks the user to try one thing,
 * shows when it worked, and moves on with Continue, whether or not they tried.
 *
 * A run lives only in memory: it ends when the user leaves the tutorial screen with the Panel
 * closed, or Android stops Chronotif, and the real Panel is back at once. Only the step reached
 * is saved, so the part continues there next time.
 */
object Tutorial {

    enum class Part(val title: Int, val desc: Int) {
        NOTIFICATIONS(R.string.tutorial_notifications, R.string.tutorial_notifications_desc),
    }

    /** One step: what to try, and the moment it counts as tried. */
    class Step(val title: Int, val body: (Context) -> CharSequence, val tried: (Run, Event) -> Boolean, val offersTile: Boolean = false)

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

    /** The tutorial screen is up; while it isn't, closing the Panel ends the run. */
    var screenShown = false
        set(v) {
            field = v
            if (!v && !panelShown) pause()
        }
    private var panelShown = false

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
        lateinit var r: Run
        r = Run(part, now, samples(app, part, now) { onEvent(r, it) })
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
        panelShown = false
        Panel.drop()
        changed()
    }

    fun next(context: Context) {
        val r = run ?: return
        if (r.finished) return
        if (r.step == r.steps.lastIndex) {
            r.finished = true
            Prefs.tutorialDone = Prefs.tutorialDone + r.part.name
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
            Event.PANEL_OPENED -> panelShown = true
            Event.PANEL_CLOSED -> panelShown = false
            Event.CHECKED -> r.checked = true
            Event.STACK_OPENED -> r.stackOpened = true
            else -> {}
        }
        if (run !== r) return
        if (!r.finished && !r.tried && r.steps[r.step].tried(r, e)) r.tried = true
        changed()
        if (e == Event.PANEL_CLOSED && !screenShown) pause()
    }

    // --- Notifications

    private const val NEW_AND_EARLIER = 1
    private const val NEW_MESSAGE_DELAY_MS = 2500L

    private val notificationSteps = listOf(
        Step(R.string.tutorial_open_title, { ctx ->
            ctx.getString(if (Prefs.statusSwipeEnabled && StatusBarSwipe.isEnabled(ctx)) R.string.tutorial_open_body_swipe
            else R.string.tutorial_open_body)
        }, { _, e -> e == Event.PANEL_OPENED }, offersTile = true),
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

    private const val MINUTE = 60_000L

    private fun samples(app: Context, part: Part, now: Long, onEvent: (Event) -> Unit): TutorialSource {
        return when (part) {
            Part.NOTIFICATIONS -> TutorialSource(listOf(
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
