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
import android.widget.FrameLayout
import app.chronotif.ui.Panel
import app.chronotif.ui.Ui
import app.chronotif.ui.dp
import kotlin.math.abs

/**
 * An always-available handle on the screen edge, in the shape and style the user picks (a half
 * circle outline by default). Tap it, or swipe down or inward on it, to open the panel; hold it to open
 * Search. Hold it and drag to move it: it snaps to the nearer side at the height it's let go.
 * What's drawn spans the area that takes touches, so it's easy to hit and never catches a touch
 * where nothing shows; the original thin line stays a choice, in its wider touch area. It lights up
 * while anything is NEW, in the accent colour unless another (or none) is picked.
 *
 * It steps aside while a fullscreen app hides the status bar (the keyboard can't be followed: Android
 * tells only the app being typed in), and asks Android to keep the Back gesture off it. MIUI/HyperOS keep their own Back strip on the lower
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

    private val onChange: () -> Unit = { view?.newCount = NotifStore.newCount() }

    companion object {
        /** Width and height of each [Prefs.handleShape] in dp, and the circle's gap from the edge. */
        private val SIZES = mapOf(
            Prefs.SHAPE_TAB to (20 to 120),
            Prefs.SHAPE_HALF_SMALL to (28 to 56),
            Prefs.SHAPE_HALF_LARGE to (36 to 72),
            Prefs.SHAPE_CIRCLE to (44 to 44),
            Prefs.SHAPE_LINE to (24 to 110),
        )
        private const val CIRCLE_GAP_DP = 2

        /** Width and height in dp of a handle of [shape]. */
        fun size(shape: Int): Pair<Int, Int> = SIZES[shape] ?: SIZES.getValue(Prefs.SHAPE_HALF_SMALL)

        /** The handle's usual colour, as RGB: see [Prefs.handleColour]. */
        fun idleColour(colour: Int = Prefs.handleColour): Int = when (colour) {
            Prefs.COLOUR_WHITE -> 0xFFFFFF
            Prefs.COLOUR_BLACK -> 0x1C1C1E
            else -> 0x9A9AA2
        }

        /** The handle's colour while anything is New, as RGB, or null to keep the usual one: see [Prefs.handleNewColour]. */
        fun newColour(context: Context, colour: Int = Prefs.handleNewColour): Int? = when (colour) {
            Prefs.NEW_UNCHANGED -> null
            Prefs.NEW_BLUE -> 0x0A84FF
            Prefs.NEW_GREEN -> 0x30D158
            Prefs.NEW_ORANGE -> 0xFF9F0A
            Prefs.NEW_RED -> 0xFF453A
            Prefs.NEW_PURPLE -> 0xBF5AF2
            else -> Ui.accent(context) and 0xFFFFFF
        }

        /**
         * A still picture of a right-side handle of [shape] in the current style and colours, against
         * a line standing for the screen edge: drawn by the handle's own code, for settings.
         */
        fun preview(context: Context, shape: Int): View {
            val (w, h) = size(shape)
            val handle = HandleView(context, { _, _ -> }, { _, _ -> }, preview = true).apply {
                this.shape = shape
                look = Prefs.handleLook
                idle = idleColour()
                lit = newColour(context)
            }
            return FrameLayout(context).apply {
                addView(View(context).apply { setBackgroundColor(0x40808080) },
                    FrameLayout.LayoutParams(context.dp(2), -1, Gravity.END))
                val gap = if (shape == Prefs.SHAPE_CIRCLE) context.dp(CIRCLE_GAP_DP) else 0
                addView(handle, FrameLayout.LayoutParams(context.dp(w), context.dp(h), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = context.dp(2) + gap
                })
            }
        }
    }

    fun show() {
        if (view == null) {
            val v = HandleView(ctx, ::drag, ::drop)
            v.setOnApplyWindowInsetsListener { _, insets ->
                val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
                if (hidden != barHidden) {
                    barHidden = hidden
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
        val away = (landscape && Prefs.handleHideLandscape) || barHidden
        // Stepped aside, the window stays (so it keeps hearing about the status bar) but draws
        // nothing and takes no touches; at no opacity it doesn't block touches below it.
        params.alpha = if (away) 0f else 1f
        params.flags = if (away) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        v.right = Prefs.handleRight
        v.idle = idleColour()
        v.lit = newColour(ctx)
        v.shape = Prefs.handleShape
        v.look = Prefs.handleLook
        v.newCount = NotifStore.newCount()
        layout()
        runCatching { wm.updateViewLayout(v, params) }
    }

    // Physical sides: the setting says left or right, whatever the language's direction.
    @SuppressLint("RtlHardcoded")
    private fun layout() {
        val screenH = wm.currentWindowMetrics.bounds.height()
        val (w, h) = size(Prefs.handleShape)
        params.width = ctx.dp(w)
        params.height = ctx.dp(h)
        params.gravity = Gravity.TOP or if (Prefs.handleRight) Gravity.RIGHT else Gravity.LEFT
        params.x = if (Prefs.handleShape == Prefs.SHAPE_CIRCLE) ctx.dp(CIRCLE_GAP_DP) else 0
        params.y = ((screenH - params.height) * Prefs.handleY.coerceIn(0f, 1f)).toInt()
    }

    /** Follows a held handle's drag, centred on the finger; it faces the nearer side as it goes. */
    @SuppressLint("RtlHardcoded")
    private fun drag(x: Float, y: Float) {
        val v = view ?: return
        val bounds = wm.currentWindowMetrics.bounds
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = (x - params.width / 2f).toInt().coerceIn(0, bounds.width() - params.width)
        params.y = (y - params.height / 2f).toInt().coerceIn(0, bounds.height() - params.height)
        v.right = x > bounds.width() / 2f
        runCatching { wm.updateViewLayout(v, params) }
    }

    /** Let go: the handle snaps to the nearer side at this height, which settings show too. */
    private fun drop(x: Float, y: Float) {
        val bounds = wm.currentWindowMetrics.bounds
        Prefs.handleRight = x > bounds.width() / 2f
        Prefs.handleY = ((y - params.height / 2f) / (bounds.height() - params.height)).coerceIn(0f, 1f)
        update()
    }

    @SuppressLint("ViewConstructor")
    private class HandleView(
        context: Context,
        private val onDrag: (Float, Float) -> Unit,
        private val onDrop: (Float, Float) -> Unit,
        /** Only drawn, for settings: no touches, no Back-gesture exclusion. */
        private val preview: Boolean = false,
    ) : View(context) {
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
        /** The usual colour, as RGB: see [Prefs.handleColour]. */
        var idle = 0x9A9AA2
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        /** The colour while anything is New, as RGB, or null to keep [idle]: see [Prefs.handleNewColour]. */
        var lit: Int? = 0
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
        private val trigger = context.dp(22).toFloat()
        private val tab = RectF()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        /** Held past the long-press time: lifting opens Search, dragging moves the handle. */
        private var held = false
        private var dragging = false
        private var downX = 0f
        private var downY = 0f
        private var fired = false

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (preview) return
            // Pixel-style Back gestures leave the tab alone; MIUI's own Back strip ignores this.
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }

        /** [rgb] at [alpha] (0 to 255). */
        private fun tint(rgb: Int, alpha: Int) = (alpha shl 24) or (rgb and 0xFFFFFF)

        /** Lit while anything is New, unless the New colour is set to stay unchanged. */
        private val on get() = newCount > 0 && lit != null
        /** The colour to draw in right now, as RGB. */
        private val hue get() = if (on) lit!! else idle
        /** White text on the count badge, black on a light one. */
        private val badgeText get() = if (Color.luminance(tint(hue, 255)) > 0.6f) Color.BLACK else Color.WHITE

        override fun onDraw(canvas: Canvas) {
            when (shape) {
                Prefs.SHAPE_LINE -> drawLine(canvas)
                Prefs.SHAPE_TAB -> drawTab(canvas)
                else -> drawRing(canvas)
            }
        }

        private fun drawTab(canvas: Canvas) {
            val solid = look == Prefs.LOOK_SOLID
            fill.color = when {
                solid -> tint(hue, if (on) 255 else 0xC7)
                else -> tint(hue, if (on) 0x4D else 0x52)
            }
            line.color = 0x4DFFFFFF
            line.strokeWidth = 1f
            val w = width.toFloat()
            val h = height.toFloat()
            // Rounded on the inner side only: the outer corners sit past the screen edge.
            val r = context.dp(10).toFloat()
            if (right) tab.set(0f, 0f, w + r, h) else tab.set(-r, 0f, w, h)
            canvas.drawRoundRect(tab, r, r, fill)
            if (solid) return
            val edge = if (right) 0.5f else w - 0.5f
            canvas.drawLine(edge, r, edge, h - r, line)
            val cx = w / 2
            val cy = h / 2
            if (look == Prefs.LOOK_COUNT && newCount > 0) {
                fill.color = tint(hue, 255)
                canvas.drawCircle(cx, cy, context.dp(9).toFloat(), fill)
                drawCount(canvas, cx, cy, badgeText)
                return
            }
            // A grip line in the middle.
            fill.color = tint(hue, if (on) 255 else 0xF2)
            val gw = context.dp(if (on) 6 else 5).toFloat()
            val gh = h * 0.5f
            canvas.drawRoundRect(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2, gw / 2, gw / 2, fill)
        }

        /** The original look, kept for those who like it: a slim line, lit while anything is New. */
        private fun drawLine(canvas: Canvas) {
            val w = context.dp(if (on) 5 else 4).toFloat()
            val h = height * 0.62f
            val inset = context.dp(3).toFloat()
            val left = if (right) width - inset - w else inset
            val top = (height - h) / 2
            linePaint.color = tint(hue, if (on) 255 else 0x99)
            canvas.drawRoundRect(left, top, left + w, top + h, w / 2, w / 2, linePaint)
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            setShadowLayer(context.dp(2).toFloat(), 0f, 0f, 0x55000000)
        }

        /**
         * Circles and half circles are only a border, so what's behind shows through: lit while
         * anything is New, the bold style thicker. Subtle with count puts the number inside.
         * A half circle is a circle centred on the screen edge, its other half off screen.
         */
        private fun drawRing(canvas: Canvas) {
            val solid = look == Prefs.LOOK_SOLID
            val stroke = context.dp(if (solid) 4 else 3).toFloat()
            val half = shape != Prefs.SHAPE_CIRCLE
            val cy = height / 2f
            val c = if (!half) width / 2f else if (right) width.toFloat() else 0f
            val r = (if (half) width.toFloat() else width / 2f) - stroke / 2 - 1f
            // A faint contrasting edge, so the ring still shows on a background of its own colour.
            line.strokeWidth = stroke + 2f
            line.color = if (Color.luminance(tint(hue, 255)) < 0.2f) 0x40FFFFFF else 0x40000000
            canvas.drawCircle(c, cy, r, line)
            line.strokeWidth = stroke
            line.color = tint(hue, if (on) 255 else if (solid) 0xF2 else 0xD9)
            canvas.drawCircle(c, cy, r, line)
            if (look == Prefs.LOOK_COUNT && newCount > 0) {
                val tx = if (!half) c else if (right) width * 0.6f else width * 0.4f
                drawCount(canvas, tx, cy, tint(hue, 255))
            }
        }

        private fun drawCount(canvas: Canvas, x: Float, y: Float, colour: Int) {
            count.color = colour
            val label = if (newCount > 99) "99" else newCount.toString()
            canvas.drawText(label, x, y - (count.ascent() + count.descent()) / 2, count)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (preview) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    fired = false
                    held = false
                    dragging = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> when {
                    held -> {
                        if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) dragging = true
                        if (dragging) onDrag(e.rawX, e.rawY)
                    }
                    !fired -> {
                        val dx = e.rawX - downX
                        val inward = if (right) -dx else dx
                        if (e.rawY - downY > trigger || inward > trigger) open()
                        else if (abs(dx) > trigger || abs(e.rawY - downY) > trigger) removeCallbacks(longPress)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    when {
                        dragging -> onDrop(e.rawX, e.rawY)
                        held -> runCatching { SearchActivity.open(context) }
                        !fired && abs(e.rawX - downX) < trigger && abs(e.rawY - downY) < trigger -> open()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (dragging) onDrop(e.rawX, e.rawY)
                }
            }
            return true
        }

        /**
         * Held: the buzz says it can be moved now. Let go without moving and Search opens
         * (ADR 0008); drag and the handle follows.
         */
        private val longPress = Runnable {
            if (fired) return@Runnable
            fired = true
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        private fun open() {
            removeCallbacks(longPress)
            fired = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runCatching { Panel.open(context) }
        }
    }
}
