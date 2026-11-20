// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.HeaderDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The panel header: a clock and the date, set up by the header settings. Android decides what
 * they say (12/24h, the locale's date order); the settings decide what shows, where and how big
 * (ADR 0005). Tapping the clock opens the alarms, the date opens the calendar and the gear opens
 * Android's Settings, as in the stock shade. The rest of the header isn't a tap target, so a tap there still closes the panel.
 */
internal object PanelHeader {

    /** Rebuilds [header]'s content from the current settings. */
    fun fill(header: LinearLayout, host: NotifList.Host) {
        val ctx = header.context
        header.removeAllViews()
        // The clock and date sit at the start and the gear at the end. Space between them isn't
        // a tap target, so a tap there still closes the panel.
        val start = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        }
        header.addView(row, Ui.lp())
        if (Prefs.headerSettings) {
            row.addView(settings(ctx).apply {
                tapTarget(host, R.string.header_open_settings) { listOf(settingsIntent()) }
            }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)).apply { gravity = Gravity.TOP })
        }
        val date = date(ctx).apply {
            tapTarget(host, R.string.header_open_calendar) { listOf(calendarIntent(System.currentTimeMillis())) }
        }
        if (!Prefs.headerClock) {
            start.addView(date, Ui.lp(-2))
            return
        }
        val clock = clock(ctx, Prefs.headerClockSize.toFloat()).apply {
            tapTarget(host, R.string.header_open_alarms) { alarmsIntents(ctx) }
        }
        start.addView(ClockDateRow(ctx, clock, date, Prefs.headerDateBeside, Prefs.headerDateAlign))
    }

    fun settingsIntent() = Intent(Settings.ACTION_SETTINGS)

    fun alarmsIntent() = Intent(AlarmClock.ACTION_SHOW_ALARMS)

    /**
     * The alarms screen, then the clock app's own start screen. Clock apps guard the alarms
     * screen with SET_ALARM, which the manifest asks for; one that still refuses (or that some
     * ROM guards with something else) is opened the plain way instead of the tap doing nothing.
     */
    fun alarmsIntents(ctx: Context): List<Intent> {
        val pm = ctx.packageManager
        val clockApp = pm.resolveActivity(alarmsIntent(), 0)?.activityInfo?.packageName
            ?.let { pm.getLaunchIntentForPackage(it) }
        return listOfNotNull(alarmsIntent(), clockApp)
    }

    fun calendarIntent(now: Long) = Intent(
        Intent.ACTION_VIEW,
        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(now.toString()).build(),
    )

    fun datePattern(locale: Locale = Locale.getDefault()): String = DateFormat.getBestDateTimePattern(
        locale,
        HeaderDate.skeleton(Prefs.headerWeekday, Prefs.headerYear, Prefs.headerShortNames),
    )

    /** Same face as the lock-screen clock; [TextClock] picks 12 or 24h from Android's setting. */
    private fun clock(ctx: Context, sp: Float) = TextClock(ctx).apply {
        format12Hour = "h:mm"
        format24Hour = "H:mm"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Palette(ctx).onScrim)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        includeFontPadding = false
        maxLines = 1
        setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x40000000)
    }

    /** The gear, white on the scrim like the clock. */
    private fun settings(ctx: Context) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_settings)
        imageTintList = ColorStateList.valueOf(Palette(ctx).onScrim)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = ctx.getString(R.string.header_open_settings)
    }

    /** A [TextClock] too, so the date turns over at midnight while the panel is open. */
    private fun date(ctx: Context) = TextClock(ctx).apply {
        val pattern = datePattern()
        format12Hour = pattern
        format24Hour = pattern
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette(ctx).onScrimSecondary)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
        Ui.shadow(this)
    }

    /**
     * A button for TalkBack ("Open alarms") that opens the first of [intents] Android lets it
     * start; does nothing when none can be opened. On the lock-screen view it asks to unlock first.
     */
    fun View.tapTarget(host: NotifList.Host, label: Int, intents: () -> List<Intent>) {
        background = Ui.pressable(0, dp(8).toFloat(), Palette(context).pill)
        setOnClickListener {
            host.guarded {
                val opened = intents().any { intent ->
                    try {
                        host.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                }
                if (opened) host.launched()
            }
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(v: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(v, info)
                info.className = Button::class.java.name
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, context.getString(label)),
                )
            }
        }
    }
}

/**
 * The clock with the date under it, or beside it when it fits. A date that doesn't fit beside
 * the clock (large fonts, a narrow screen) moves under it instead of being cut off.
 */
@SuppressLint("ViewConstructor")
internal class ClockDateRow(
    context: Context,
    private val clock: TextView,
    private val date: TextView,
    private val wantBeside: Boolean,
    private val align: Int,
) : ViewGroup(context) {

    /** Whether the date is beside the clock in the last layout. */
    var beside = false
        private set

    private val gapBeside = dp(12)
    private val gapUnder = dp(2)
    private var clockY = 0
    private var dateY = 0

    init {
        addView(clock)
        addView(date)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val avail = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthSpec)
        val atMost = MeasureSpec.makeMeasureSpec(min(avail, 1 shl 24), MeasureSpec.AT_MOST)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        clock.measure(atMost, unspecified)
        date.measure(unspecified, unspecified)
        beside = wantBeside && clock.measuredWidth + gapBeside + date.measuredWidth <= avail
        val width: Int
        if (beside) {
            clockY = 0
            dateY = when (align) {
                Prefs.DATE_ALIGN_TOP -> 0
                Prefs.DATE_ALIGN_CENTER -> (clock.measuredHeight - date.measuredHeight) / 2
                else -> clock.baseline - date.baseline
            }
            val top = min(clockY, dateY)
            clockY -= top
            dateY -= top
            width = clock.measuredWidth + gapBeside + date.measuredWidth
        } else {
            date.measure(atMost, unspecified)
            clockY = 0
            dateY = clock.measuredHeight + gapUnder
            width = max(clock.measuredWidth, date.measuredWidth)
        }
        val height = max(clockY + clock.measuredHeight, dateY + date.measuredHeight)
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        fun place(v: View, x: Int, y: Int) {
            val left = if (rtl) w - x - v.measuredWidth else x
            v.layout(left, y, left + v.measuredWidth, y + v.measuredHeight)
        }
        place(clock, 0, clockY)
        place(date, if (beside) clock.measuredWidth + gapBeside else 0, dateY)
    }
}
