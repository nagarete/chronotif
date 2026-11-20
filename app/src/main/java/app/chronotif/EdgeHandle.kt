// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.chronotif.ui.SearchActivity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import app.chronotif.ui.Panel
import app.chronotif.ui.Ui
import app.chronotif.ui.dp
import kotlin.math.abs

/**
 * An always-available handle on the screen edge, in the shape and fill the user picks (a half
 * circle by default). Tap it, or swipe down or inward on it, to open the panel; hold it to open
 * Search. What's drawn fills the area that takes touches, so it's easy to hit and never catches a
 * touch where nothing shows. It takes the accent colour while anything is NEW.
 *
 * It steps aside while a fullscreen app hides the status bar or the keyboard is up, and asks
 * Android to keep the Back gesture off it. MIUI/HyperOS keep their own Back strip on the lower
 * part of the edges regardless, which takes inward swipes there; taps and downward swipes still
 * reach the tab. Idle cost is one small window with no timers.
 */
class EdgeHandle(service: Context) {
    private val ctx: Context = service.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
        .let { service.createDisplayContext(it) }
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: HandleView? = null
    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { title = "Chronotif handle" }

    /** Reported by the window's insets: a fullscreen app has hidden the status bar. */
    private var barHidden = false
    private var keyboardUp = false

    private val onChange: () -> Unit = { view?.newCount = NotifStore.newCount() }

    private companion object {
        /** Width and height of each [Prefs.handleShape] in dp, and the circle's gap from the edge. */
        val SIZES = mapOf(
            Prefs.SHAPE_TAB to (20 to 120),
            Prefs.SHAPE_HALF_SMALL to (28 to 56),
            Prefs.SHAPE_HALF_LARGE to (36 to 72),
            Prefs.SHAPE_CIRCLE to (44 to 44),
        )
        const val CIRCLE_GAP_DP = 2
    }

    fun show() {
        if (view == null) {
            val v = HandleView(ctx)
            v.setOnApplyWindowInsetsListener { _, insets ->
                val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
                val keyboard = insets.isVisible(WindowInsets.Type.ime())
                if (hidden != barHidden || keyboard != keyboardUp) {
                    barHidden = hidden
                    keyboardUp = keyboard
                    update()
                }
                insets
            }
            view = v
            layout()
            try {
                wm.addView(v, params)
            } catch (_: RuntimeException) {
                view = null
                return
            }
            NotifStore.addListener(onChange)
        }
        update()
    }

    fun hide() {
        NotifStore.removeListener(onChange)
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
    }

    fun update() {
        val v = view ?: return
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val away = (landscape && Prefs.handleHideLandscape) || barHidden || keyboardUp
        // Stepped aside, the window stays (so it keeps hearing about the status bar and keyboard)
        // but draws nothing and takes no touches; at no opacity it doesn't block touches below it.
        params.alpha = if (away) 0f else 1f
        params.flags = if (away) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        v.right = Prefs.handleRight
        v.shape = Prefs.handleShape
        v.look = Prefs.handleLook
        v.newCount = NotifStore.newCount()
        layout()
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun layout() {
        val screenH = wm.currentWindowMetrics.bounds.height()
        val (w, h) = SIZES[Prefs.handleShape] ?: SIZES.getValue(Prefs.SHAPE_HALF_SMALL)
        params.width = ctx.dp(w)
        params.height = ctx.dp(h)
        params.gravity = Gravity.TOP or if (Prefs.handleRight) Gravity.END else Gravity.START
        params.x = if (Prefs.handleShape == Prefs.SHAPE_CIRCLE) ctx.dp(CIRCLE_GAP_DP) else 0
        params.y = ((screenH - params.height) * Prefs.handleY.coerceIn(0f, 1f)).toInt()
    }

    @SuppressLint("ViewConstructor")
    private class HandleView(context: Context) : View(context) {
        var newCount = 0
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        var right = true
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        /** [Prefs.handleShape]. */
        var shape = Prefs.SHAPE_HALF_SMALL
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        /** [Prefs.handleLook]. */
        var look = Prefs.LOOK_FROSTED
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val count = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = context.dp(11).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        private val accent = Ui.accent(context)
        private val trigger = context.dp(22).toFloat()
        private val tab = RectF()
        private var downX = 0f
        private var downY = 0f
        private var fired = false

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            // Pixel-style Back gestures leave the tab alone; MIUI's own Back strip ignores this.
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }

        override fun onDraw(canvas: Canvas) {
            val fresh = newCount > 0
            val solid = look == Prefs.LOOK_SOLID
            fill.color = when {
                solid && fresh -> accent
                solid -> 0xC76E6E76.toInt()
                fresh -> (accent and 0x00FFFFFF) or 0x4D000000
                else -> 0x52808088
            }
            line.color = 0x4DFFFFFF
            line.strokeWidth = 1f
            val w = width.toFloat()
            val h = height.toFloat()
            // Where the mark sits: the middle of what shows.
            val cx: Float
            when (shape) {
                Prefs.SHAPE_TAB -> {
                    // Rounded on the inner side only: the outer corners sit past the screen edge.
                    val r = context.dp(10).toFloat()
                    if (right) tab.set(0f, 0f, w + r, h) else tab.set(-r, 0f, w, h)
                    canvas.drawRoundRect(tab, r, r, fill)
                    if (!solid) canvas.drawLine(if (right) 0.5f else w - 0.5f, r, if (right) 0.5f else w - 0.5f, h - r, line)
                    cx = w / 2
                }
                Prefs.SHAPE_CIRCLE -> {
                    canvas.drawCircle(w / 2, h / 2, w / 2, fill)
                    if (!solid) canvas.drawCircle(w / 2, h / 2, w / 2 - 0.5f, line)
                    cx = w / 2
                }
                else -> {
                    // A half circle, its flat side on the screen edge.
                    val edge = if (right) w else 0f
                    canvas.drawCircle(edge, h / 2, w, fill)
                    if (!solid) canvas.drawCircle(edge, h / 2, w - 0.5f, line)
                    cx = if (right) w * 0.58f else w * 0.42f
                }
            }
            if (solid) return
            val cy = h / 2
            if (look == Prefs.LOOK_COUNT && fresh) {
                val r = context.dp(9).toFloat()
                fill.color = accent
                canvas.drawCircle(cx, cy, r, fill)
                val label = if (newCount > 99) "99" else newCount.toString()
                canvas.drawText(label, cx, cy - (count.ascent() + count.descent()) / 2, count)
                return
            }
            fill.color = if (fresh) accent else 0xF2787880.toInt()
            if (shape == Prefs.SHAPE_TAB) {
                val gw = context.dp(if (fresh) 6 else 5).toFloat()
                val gh = h * 0.5f
                canvas.drawRoundRect(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2, gw / 2, gw / 2, fill)
            } else {
                canvas.drawCircle(cx, cy, context.dp(if (fresh) 5 else 4).toFloat(), fill)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fired = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (!fired) {
                    val dx = e.rawX - downX
                    val inward = if (right) -dx else dx
                    if (e.rawY - downY > trigger || inward > trigger) open()
                    else if (abs(dx) > trigger || abs(e.rawY - downY) > trigger) removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!fired && abs(e.rawX - downX) < trigger && abs(e.rawY - downY) < trigger) open()
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return true
        }

        /** Holding the handle opens Search instead of the panel (ADR 0008). */
        private val longPress = Runnable {
            if (fired) return@Runnable
            fired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            runCatching { SearchActivity.open(context) }
        }

        private fun open() {
            removeCallbacks(longPress)
            fired = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runCatching { Panel.open(context) }
        }
    }
}
