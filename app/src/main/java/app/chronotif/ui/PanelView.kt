// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import app.chronotif.NotifStore
import app.chronotif.Prefs
import kotlin.math.abs

/**
 * The panel's content: scrim, header and notification list, with its close gestures (outside
 * tap, tap below the list, upward fling at the end of the list) and the pull-down that opens App
 * search. Hosted by [Panel] as an overlay
 * window, or by [PanelActivity] when overlays aren't allowed. Being on screen for a moment (or
 * interacting) counts as a meaningful check: on [dismissed], what was shown moves to EARLIER.
 *
 * The window, dim and header draw first; the cards are built right after that frame, so building
 * them never holds the panel back. Until they're on screen, nothing has been seen, so closing
 * before then is no check.
 */
@SuppressLint("ViewConstructor")
class PanelView(
    context: Context,
    private val host: NotifList.Host,
    blur: Boolean,
    windowDims: Boolean = false,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    companion object {
        private const val CHECK_DWELL_MS = 1500L
        /** How long the cards take to come in: a pull only counts from a touch that starts after it. */
        private const val OPEN_ANIMATION_MS = 260L

        /**
         * How dark the backdrop is. Over a blur it's a light tint, like the stock shade, so the
         * wallpaper or app behind keeps its brightness; without a blur it dims hard so the list
         * doesn't compete with what's behind.
         */
        fun scrimAlpha(blur: Boolean) = if (blur) 0x33 / 255f else 0xD9 / 255f

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
    private val header: LinearLayout
    private val list: NotifList
    private val main = Handler(Looper.getMainLooper())
    private var showing = false
    private var revealed = false
    /** When the cards came on screen; 0 until then. */
    private var shownAt = 0L
    private var interacted = false
    private var closing = false
    private var blankDownX = 0f
    private var blankDownY = 0f
    private var blankTapCandidate = false

    private val render: () -> Unit = { if (shownAt != 0L) list.render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 60_000)
        }
    }

    init {
        scrim = View(context).apply {
            // When the host window dims, this only catches outside taps.
            if (!windowDims) setBackgroundColor((scrimAlpha(blur) * 255).toInt() shl 24)
            setOnClickListener { close() }
        }
        addView(scrim, LayoutParams(-1, -1))

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Match the horizontal inset of notification and media cards.
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        PanelHeader.fill(header, host)
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
            // The window doesn't resize for the keyboard, so keep what's being typed above it.
            if (ime.bottom > 0) scroll.post(::revealFocusedEditor)
            insets
        }
        list = NotifList(host, content, onLock = false)
    }

    /** Scrolls the focused reply box above the keyboard; the scroll view's bottom padding is the keyboard. */
    private fun revealFocusedEditor() {
        val editor = findFocus() as? EditText ?: return
        val r = Rect(0, 0, editor.width, editor.height)
        scroll.offsetDescendantRectToMyCoords(editor, r)
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom - dp(8)
        if (r.bottom > visibleBottom) scroll.smoothScrollBy(0, r.bottom - visibleBottom)
    }

    fun focus(key: String, replyIndex: Int) {
        list.focus(key, replyIndex)
        interacted = true
        // Before the cards are first built, building them picks this up.
        if (shownAt != 0L) list.render()
    }

    /** The panel is on screen: show the header now, the cards once it has drawn, then follow notifications. */
    fun shown() {
        showing = true
        // Picks up header settings changed since the panel was built.
        PanelHeader.fill(header, host)
        NotifStore.addListener(render)
        afterNextFrame {
            if (!showing || shownAt != 0L) return@afterNextFrame
            shownAt = System.currentTimeMillis()
            list.render()
            revealCards()
        }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 60_000)
    }

    /** Runs [action] once the next frame has been drawn: posted from its pre-draw, it runs after the draw. */
    private fun afterNextFrame(action: () -> Unit) {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                main.post(action)
                return true
            }
        })
        invalidate()
    }

    /** The panel left the screen, by any route: stop following, and record the check. */
    fun dismissed() {
        showing = false
        NotifStore.removeListener(render)
        main.removeCallbacks(ticker)
        if (shownAt == 0L) return
        if (interacted || System.currentTimeMillis() - shownAt >= CHECK_DWELL_MS) {
            Prefs.markChecked(list.shownUpTo)
        }
        shownAt = 0L
    }

    // --- Gestures: upward fling at the bottom of the list closes, like pushing a shade up.

    private var downX = 0f
    private var downY = 0f
    private var atBottomOnDown = false
    private var tracker: VelocityTracker? = null
    private var edgeSwipe = false
    private var swallowGesture = false
    private var openedAt = 0L

    /** Pulling down at the top of the list opens Search, once the panel has finished opening. */
    private val pull = PullToSearch(
        list = scroll,
        atTop = { !scroll.canScrollVertically(-1) },
        ready = { !closing && shownAt != 0L && android.os.SystemClock.uptimeMillis() - openedAt >= OPEN_ANIMATION_MS },
        open = { SearchOverlay.show(this, host) },
    )

    // --- Back swipe from a screen edge. Over the home screen, MIUI turns its Back gesture off
    // and the swipe reaches the panel window instead; elsewhere the system's gesture wins.

    @SuppressLint("Recycle")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallowGesture = false else return true
        }
        if (pull.onTouch(ev) { cancelChildren(ev) }) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interacted = true
                downX = ev.rawX
                downY = ev.rawY
                edgeSwipe = ev.x < dp(24) || ev.x > width - dp(24)
                atBottomOnDown = !scroll.canScrollVertically(1)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> edgeSwipe = false
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (edgeSwipe) {
                    val dx = abs(ev.rawX - downX)
                    val dy = abs(ev.rawY - downY)
                    if (dx > dp(56) && dx > dy * 2) {
                        edgeSwipe = false
                        cancelChildren(ev)
                        swallowGesture = true
                        close()
                        return true
                    } else if (dy > dp(48)) {
                        edgeSwipe = false
                    }
                }
            }
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

    /** Children stop tracking this gesture (e.g. a card swipe). */
    private fun cancelChildren(ev: MotionEvent) {
        MotionEvent.obtain(ev).also {
            it.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    // --- Animation ---

    /** The backdrop fades in; the header is there from the first frame, and the cards follow in [revealCards]. */
    fun animateIn() {
        openedAt = android.os.SystemClock.uptimeMillis()
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
    }

    /** The cards' first arrival slides in; coming back to the panel (the activity resuming) doesn't. */
    private fun revealCards() {
        openedAt = android.os.SystemClock.uptimeMillis()
        if (revealed) return
        revealed = true
        content.alpha = 0f
        content.translationY = -dp(24).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(OPEN_ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    val isClosing get() = closing

    /** Animates out, then asks the host to remove the panel. */
    fun close() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(160).start()
        scroll.animate().alpha(0f).translationY(-dp(64).toFloat()).setDuration(160)
            .withEndAction { onClose() }.start()
    }
}
