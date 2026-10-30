// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.chronotif.ui.Panel
import app.chronotif.ui.Ui
import app.chronotif.ui.dp
import kotlin.math.abs

/**
 * A slim always-available handle on the screen edge. Tap it, or swipe down/inward on it, to
 * open the panel. It is tinted with the accent colour while anything is NEW, so the attention
 * model is visible at a glance. Idle cost is one tiny window with no timers.
 */
class EdgeHandle(service: Context) {
    private val ctx: Context = service.getSystemService(DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
        .let { service.createDisplayContext(it) }
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: HandleView? = null
    private val params = WindowManager.LayoutParams(
        ctx.dp(24), ctx.dp(110),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { title = "Chronotif handle" }

    private val onChange: () -> Unit = { view?.fresh = NotifStore.newCount() > 0 }

    fun show() {
        if (view == null) {
            val v = HandleView(ctx)
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
        v.visibility = if (landscape && Prefs.handleHideLandscape) View.GONE else View.VISIBLE
        v.right = Prefs.handleRight
        v.fresh = NotifStore.newCount() > 0
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
        var fresh = false
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        var right = true
            set(v) {
                field = v
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accent = Ui.accent(context)
        private val trigger = context.dp(22).toFloat()
        private var downX = 0f
        private var downY = 0f
        private var fired = false

        override fun onDraw(canvas: Canvas) {
            val w = context.dp(if (fresh) 5 else 4).toFloat()
            val h = height * 0.62f
            val inset = context.dp(3).toFloat()
            val left = if (right) width - inset - w else inset
            val top = (height - h) / 2
            paint.color = if (fresh) accent else 0x99808086.toInt() // visible on light and dark
            paint.setShadowLayer(context.dp(2).toFloat(), 0f, 0f, 0x55000000)
            canvas.drawRoundRect(left, top, left + w, top + h, w / 2, w / 2, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fired = false
                }
                MotionEvent.ACTION_MOVE -> if (!fired) {
                    val dx = e.rawX - downX
                    val inward = if (right) -dx else dx
                    if (e.rawY - downY > trigger || inward > trigger) open()
                }
                MotionEvent.ACTION_UP -> if (!fired &&
                    abs(e.rawX - downX) < trigger && abs(e.rawY - downY) < trigger
                ) open()
            }
            return true
        }

        private fun open() {
            fired = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runCatching { Panel.open(context) }
        }
    }
}
