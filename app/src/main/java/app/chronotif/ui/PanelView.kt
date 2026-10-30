// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.chronotif.NotifStore
import app.chronotif.Prefs
import app.chronotif.R
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * The panel's content: scrim, header and notification list, with its close gestures (outside
 * tap, tap below the list, upward fling at the end of the list). Hosted by [Panel] as an overlay
 * window, or by [PanelActivity] when overlays aren't allowed. Being on screen for a moment (or
 * interacting) counts as a meaningful check: on [dismissed], what was shown moves to EARLIER.
 */
@SuppressLint("ViewConstructor")
class PanelView(
    context: Context,
    host: NotifList.Host,
    blur: Boolean,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    companion object {
        private const val CHECK_DWELL_MS = 1500L

        /** Only an unmoving tap that begins and ends below the list may dismiss it. */
        internal fun isEmptySpaceTap(
            startedInBlank: Boolean,
            endedInBlank: Boolean,
            dx: Float,
            dy: Float,
            slop: Float,
        ): Boolean = startedInBlank && endedInBlank &&
            abs(dx) <= slop && abs(dy) <= slop
    }

    private val scrim: View
    private val scroll: ScrollView
    private val content: LinearLayout
    private val date: TextView
    private val list: NotifList
    private val main = Handler(Looper.getMainLooper())
    private var shownAt = 0L
    private var interacted = false
    private var closing = false
    private var blankDownX = 0f
    private var blankDownY = 0f
    private var blankTapCandidate = false

    private val render: () -> Unit = { list.render() }
    private val ticker = object : Runnable {
        override fun run() {
            list.render()
            main.postDelayed(this, 60_000)
        }
    }

    init {
        scrim = View(context).apply {
            setBackgroundColor(if (blur) 0x8C000000.toInt() else 0xD9000000.toInt())
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(-1, -1))

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        val pal = Palette(context)
        date = Ui.text(context, 15f, pal.onScrimSecondary, bold = true, lines = 1)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Match the horizontal inset of notification and media cards.
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(Ui.text(context, 30f, pal.onScrim, bold = true, lines = 1).apply {
                setText(R.string.tile_label)
            })
            addView(date, Ui.lp().apply { topMargin = dp(2) })
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(content)
            // Taps on empty space between cards close the panel, like a shade.
            setOnClickListener { close() }
        }
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(column, LayoutParams(-1, -2))
        }
        // ScrollView fills the screen but its child column only wraps the cards.
        // The full-screen scroll view receives touches BELOW that column, not the scrim.
        // A passive touch listener keeps normal scrolling and all child interactions.
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        scroll.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blankDownX = ev.x
                    blankDownY = ev.y
                    blankTapCandidate = ev.y > column.bottom - scroll.scrollY
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL ->
                    blankTapCandidate = false
                MotionEvent.ACTION_MOVE -> if (blankTapCandidate &&
                    (abs(ev.x - blankDownX) > touchSlop || abs(ev.y - blankDownY) > touchSlop)) {
                    blankTapCandidate = false
                }
                MotionEvent.ACTION_UP -> {
                    val shouldClose = isEmptySpaceTap(
                        blankTapCandidate,
                        ev.y > column.bottom - scroll.scrollY,
                        ev.x - blankDownX,
                        ev.y - blankDownY,
                        touchSlop,
                    )
                    blankTapCandidate = false
                    if (shouldClose) {
                        close()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
        addView(scroll, LayoutParams(-1, -1, Gravity.TOP))
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            scroll.setPadding(bars.left, bars.top + dp(8), bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        list = NotifList(host, content, onLock = false)
    }

    fun focus(key: String, replyIndex: Int) {
        list.focus(key, replyIndex)
        interacted = true
        list.render()
    }

    /** The panel is on screen: start following notifications. */
    fun shown() {
        shownAt = System.currentTimeMillis()
        date.text = DateFormat.getDateInstance(DateFormat.FULL).format(Date())
        NotifStore.addListener(render)
        list.render()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 60_000)
    }

    /** The panel left the screen, by any route: stop following, and record the check. */
    fun dismissed() {
        NotifStore.removeListener(render)
        main.removeCallbacks(ticker)
        if (shownAt == 0L) return
        if (interacted || System.currentTimeMillis() - shownAt >= CHECK_DWELL_MS) {
            Prefs.markChecked(list.shownUpTo)
        }
        shownAt = 0L
    }

    // --- Gestures: upward fling at the bottom of the list closes, like pushing a shade up.

    private var downY = 0f
    private var atBottomOnDown = false
    private var tracker: VelocityTracker? = null

    @SuppressLint("Recycle")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interacted = true
                downY = ev.rawY
                atBottomOnDown = !scroll.canScrollVertically(1)
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
                if (atBottomOnDown && downY - ev.rawY > dp(90) && vy < -dp(600)) {
                    close()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // --- Animation ---

    fun animateIn() {
        scrim.alpha = 0f
        scroll.alpha = 0f
        scroll.translationY = -dp(48).toFloat()
        scrim.animate().alpha(1f).setDuration(180).start()
        scroll.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Animates out, then asks the host to remove the panel. */
    fun close() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(160).start()
        scroll.animate().alpha(0f).translationY(-dp(64).toFloat()).setDuration(160)
            .withEndAction { onClose() }.start()
    }
}
