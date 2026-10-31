// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.R
import app.chronotif.core.RelTime
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Int): Int = context.dp(v)

/** Colours for cards floating over a dimmed/blurred backdrop, following light/dark mode. */
class Palette(context: Context) {
    val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val card = if (dark) 0xEB2C2C2E.toInt() else 0xF2FAFAFC.toInt()
    val cardReceded = if (dark) 0xC72C2C2E.toInt() else 0xD9F2F2F5.toInt()
    val sliver1 = if (dark) 0x99303032.toInt() else 0xB3E6E6EA.toInt()
    val sliver2 = if (dark) 0x66303032.toInt() else 0x80E6E6EA.toInt()
    val text = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val textSecondary = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    val chip = if (dark) 0x1FFFFFFF else 0x14000000
    val ripple = if (dark) 0x33FFFFFF else 0x22000000
    val accent = Ui.accent(context)
    /** Section headers sit directly on the panel's dark scrim, or the bare wallpaper on the lock screen. */
    val onScrim = 0xFFFFFFFF.toInt()
    val onScrimSecondary = 0xB3FFFFFF.toInt()
    val pill = 0x33FFFFFF
}

object Ui {
    /** Edge-to-edge is the default from Android 15; opt in explicitly before that. */
    @Suppress("DEPRECATION")
    fun edgeToEdge(window: android.view.Window) {
        if (Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
    }

    fun accent(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_300) else 0xFF0A84FF.toInt()

    fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun pressable(color: Int, radius: Float, ripple: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(ripple), rounded(color, radius), rounded(-1, radius))

    fun text(context: Context, sp: Float, color: Int, bold: Boolean = false, lines: Int = 0) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            if (lines > 0) {
                maxLines = lines
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            includeFontPadding = false
            setLineSpacing(0f, 1.12f)
        }

    /** Keeps white text legible on a bright wallpaper, the way the stock lock screen does. */
    fun shadow(v: TextView) = v.setShadowLayer(v.dp(4).toFloat(), 0f, 0f, 0x66000000)

    fun clipRounded(v: View, radius: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        v.clipToOutline = true
    }

    fun clipOval(v: View) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        v.clipToOutline = true
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    fun relTime(context: Context, t: Long, now: Long = System.currentTimeMillis()): String {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when (val l = RelTime.of(now, t, startOfToday)) {
            RelTime.Label.Now -> context.getString(R.string.time_now)
            is RelTime.Label.Minutes -> context.getString(R.string.time_min, l.m)
            is RelTime.Label.Hours -> context.getString(R.string.time_hour, l.h)
            RelTime.Label.Yesterday -> context.getString(R.string.time_yesterday)
            RelTime.Label.Older -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
        }
    }
}
