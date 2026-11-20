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
 * An always-available tab on the screen edge. Tap it, or swipe down or inward on it, to open the
 * panel; hold it to open Search. What's drawn is exactly what takes touches, so it's easy to hit
 * and never catches a touch where nothing shows. It takes the accent colour while anything is NEW.
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
        ctx.dp(WIDTH_DP), ctx.dp(HEIGHT_DP),
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
        const val WIDTH_DP = 20
        const val HEIGHT_DP = 120
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
        v.look = Prefs.handleLook
        v.newCount = NotifStore.newCount()
        layout()
        runCatching { wm.updateViewLayout(v, params) }
    }

    private fun layout() {
        val screenH = wm.currentWindowMetrics.bounds.height()
        params.gravity = Gravity.TOP or if (Prefs.handleRight) Gravity.END else Gravity.START
        params.x = 0
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
            textSize = context.dp(12).toFloat()
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
            val r = context.dp(10).toFloat()
            // Rounded on the inner side only: the outer corners sit past the screen edge.
            if (right) tab.set(0f, 0f, width + r, height.toFloat()) else tab.set(-r, 0f, width.toFloat(), height.toFloat())
            if (look == Prefs.LOOK_SOLID) {
                fill.color = if (fresh) accent else 0xC76E6E76.toInt()
                canvas.drawRoundRect(tab, r, r, fill)
                return
            }
            // Frosted: see-through, with a lit inner edge and a grip line.
            fill.color = if (fresh) (accent and 0x00FFFFFF) or 0x42000000 else 0x4D808088
            canvas.drawRoundRect(tab, r, r, fill)
            line.color = 0x47FFFFFF
            line.strokeWidth = 1f
            val edge = if (right) 0.5f else width - 0.5f
            canvas.drawLine(edge, r, edge, height - r, line)
            if (look == Prefs.LOOK_COUNT && fresh) {
                val w = context.dp(16).toFloat()
                val h = context.dp(28).toFloat()
                val cx = width / 2f
                val cy = height / 2f
                fill.color = accent
                canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w / 2, w / 2, fill)
                val label = if (newCount > 99) "99" else newCount.toString()
                canvas.drawText(label, cx, cy - (count.ascent() + count.descent()) / 2, count)
                return
            }
            val gw = context.dp(if (fresh) 6 else 5).toFloat()
            val gh = height * 0.5f
            val gx = (width - gw) / 2
            val gy = (height - gh) / 2
            fill.color = if (fresh) accent else 0xF2787880.toInt()
            canvas.drawRoundRect(gx, gy, gx + gw, gy + gh, gw / 2, gw / 2, fill)
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
