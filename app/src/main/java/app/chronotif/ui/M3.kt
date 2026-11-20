// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Switch

/**
 * Material 3 colour roles for settings, in light or dark following the system. From Android 12 they
 * come from the wallpaper's tonal palettes, as the system's own settings do; before that, indigo.
 */
class M3(context: Context) {
    val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The page behind the groups. */
    val surface: Int
    /** A group's rows. */
    val item: Int
    /** The drawer's sheet. */
    val drawer: Int
    /** Tiles and unpicked choices. */
    val high: Int
    /** Off switches and empty slider track. */
    val highest: Int
    val primary: Int
    val onPrimary: Int
    val primaryContainer: Int
    val onPrimaryContainer: Int
    val text: Int
    val textSecondary: Int
    val outline: Int
    val error = if (dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val errorContainer = if (dark) 0xFF93000A.toInt() else 0xFFFFDAD6.toInt()
    val onErrorContainer = if (dark) 0xFFFFDAD6.toInt() else 0xFF410002.toInt()
    val good = if (dark) 0xFF8FD8A6.toInt() else 0xFF1F7A45.toInt()
    val goodContainer = if (dark) 0xFF1E5233.toInt() else 0xFFC4EECF.toInt()
    val ripple: Int

    init {
        if (Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int) = context.getColor(id)
            val n1900 = c(android.R.color.system_neutral1_900)
            val n1800 = c(android.R.color.system_neutral1_800)
            val n250 = c(android.R.color.system_neutral2_50)
            val n2100 = c(android.R.color.system_neutral2_100)
            if (dark) {
                surface = blend(n1900, 0xFF000000.toInt(), 0.4f)
                item = blend(n1900, n1800, 0.25f)
                drawer = n1900
                high = blend(n1900, n1800, 0.6f)
                highest = n1800
                primary = c(android.R.color.system_accent1_200)
                onPrimary = c(android.R.color.system_accent1_800)
                primaryContainer = c(android.R.color.system_accent1_700)
                onPrimaryContainer = c(android.R.color.system_accent1_100)
                text = c(android.R.color.system_neutral1_100)
                textSecondary = c(android.R.color.system_neutral2_200)
                outline = c(android.R.color.system_neutral2_400)
            } else {
                surface = n250
                item = c(android.R.color.system_neutral1_10)
                drawer = c(android.R.color.system_neutral1_10)
                high = blend(n250, n2100, 0.6f)
                highest = n2100
                primary = c(android.R.color.system_accent1_600)
                onPrimary = c(android.R.color.system_accent1_0)
                primaryContainer = c(android.R.color.system_accent1_100)
                onPrimaryContainer = c(android.R.color.system_accent1_900)
                text = n1900
                textSecondary = c(android.R.color.system_neutral2_700)
                outline = c(android.R.color.system_neutral2_500)
            }
        } else if (dark) {
            surface = 0xFF131318.toInt(); item = 0xFF1F1F25.toInt(); drawer = 0xFF1B1B21.toInt()
            high = 0xFF2A292F.toInt(); highest = 0xFF35343A.toInt()
            primary = 0xFFC0C1FF.toInt(); onPrimary = 0xFF20218A.toInt()
            primaryContainer = 0xFF37389E.toInt(); onPrimaryContainer = 0xFFE1E0FF.toInt()
            text = 0xFFE4E1E9.toInt(); textSecondary = 0xFFC7C5D0.toInt(); outline = 0xFF918F9A.toInt()
        } else {
            surface = 0xFFF1EFFA.toInt(); item = 0xFFFDFBFF.toInt(); drawer = 0xFFFDFBFF.toInt()
            high = 0xFFE9E7F2.toInt(); highest = 0xFFE3E1EC.toInt()
            primary = 0xFF4F4FC8.toInt(); onPrimary = 0xFFFFFFFF.toInt()
            primaryContainer = 0xFFE1E0FF.toInt(); onPrimaryContainer = 0xFF24247E.toInt()
            text = 0xFF1B1B21.toInt(); textSecondary = 0xFF46464F.toInt(); outline = 0xFF777680.toInt()
        }
        ripple = (text and 0xFFFFFF) or 0x1F000000
    }

    /** A shape in [color] that ripples when pressed, with corner [radii] (clockwise from top-left). */
    fun pressable(color: Int, radii: FloatArray): Drawable {
        fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadii = radii }
        return RippleDrawable(ColorStateList.valueOf(ripple), shape(color), shape(-1))
    }

    fun pressable(color: Int, radius: Float) = pressable(color, FloatArray(8) { radius })

    companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        /** Corner radii for one of [count] rows joined into a group: round outside, tight inside. */
        fun joined(index: Int, count: Int, outer: Float, inner: Float): FloatArray {
            val top = if (index == 0) outer else inner
            val bottom = if (index == count - 1) outer else inner
            return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        }
    }
}

/**
 * Rows joined into one card: the visible rows share a rounded outline, with a thin gap and tight
 * corners between them. A row shown or hidden later reshapes the group on the next layout.
 */
class SettingGroup(context: Context, private val c: M3) : LinearLayout(context) {
    private var shapedFor = ""
    private val outer = context.dp(22).toFloat()
    private val inner = context.dp(6).toFloat()

    init {
        orientation = VERTICAL
        dividerDrawable = GradientDrawable().apply { setSize(0, context.dp(2)) }
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    private fun visibleRows() = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }

    fun reshape() {
        val rows = visibleRows()
        shapedFor = rows.joinToString { System.identityHashCode(it).toString() }
        rows.forEachIndexed { i, v ->
            val radii = M3.joined(i, rows.size, outer, inner)
            v.background = if (v.isClickable) c.pressable(c.item, radii)
            else GradientDrawable().apply { setColor(c.item); cornerRadii = radii }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val now = visibleRows().joinToString { System.identityHashCode(it).toString() }
        if (now != shapedFor) post { reshape() }
    }
}

/**
 * The Material 3 switch: an outlined track with a small thumb when off, a filled track with a large
 * thumb holding a check when on. It only draws; the row it sits in takes the tap.
 */
class M3Switch(context: Context, private val c: M3) : View(context) {
    var isChecked = false
        private set
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Path()
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setChecked(on: Boolean, animate: Boolean) {
        if (on == isChecked && anim == null) return
        isChecked = on
        anim?.cancel()
        val to = if (on) 1f else 0f
        if (!animate) {
            pos = to
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(52), dp(32))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val stroke = dp(2).toFloat()
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        paint.style = Paint.Style.FILL
        paint.color = M3.blend(c.highest, c.primary, pos)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        if (pos < 1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = M3.blend(c.outline, c.primary, pos)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        }
        val r = dp(8) + (dp(12) - dp(8)) * pos + if (isPressed) dp(2).toFloat() else 0f
        val cx = h / 2 + (w - h) * (if (layoutDirection == LAYOUT_DIRECTION_RTL) 1 - pos else pos)
        paint.style = Paint.Style.FILL
        paint.color = M3.blend(c.outline, c.onPrimary, pos)
        canvas.drawCircle(cx, h / 2, r, paint)
        if (pos > 0.5f) {
            val s = dp(5).toFloat()
            check.reset()
            check.moveTo(cx - s, h / 2)
            check.lineTo(cx - s * 0.3f, h / 2 + s * 0.7f)
            check.lineTo(cx + s, h / 2 - s * 0.6f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = c.primary
            paint.alpha = ((pos - 0.5f) * 2 * 255).toInt()
            canvas.drawPath(check, paint)
            paint.alpha = 255
        }
    }

    /** Lets a row that holds this switch read as one to accessibility services. */
    class RowAccessibility(private val sw: M3Switch) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = sw.isChecked
        }
    }
}

/** How far setup is: an arc of [done] out of [total], with the count, or a check when complete. */
class ProgressRing(context: Context) : View(context) {
    var done = 0
    var total = 1
    var color = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    fun set(done: Int, total: Int, color: Int) {
        this.done = done
        this.total = total
        this.color = color
        contentDescription = "$done/$total"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(5).toFloat()
        rect.set(stroke, stroke, width - stroke, height - stroke)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = (color and 0xFFFFFF) or 0x33000000
        canvas.drawOval(rect, paint)
        paint.color = color
        if (done > 0) canvas.drawArc(rect, -90f, 360f * done / total, false, paint)
        val cx = width / 2f
        val cy = height / 2f
        if (done >= total) {
            val s = width * 0.16f
            path.reset()
            path.moveTo(cx - s, cy)
            path.lineTo(cx - s * 0.3f, cy + s * 0.7f)
            path.lineTo(cx + s, cy - s * 0.6f)
            paint.strokeWidth = dp(3).toFloat()
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.textSize = width * 0.26f
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false)
            canvas.drawText("$done/$total", cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }
}
