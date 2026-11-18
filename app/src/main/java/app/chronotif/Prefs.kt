// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.SharedPreferences
import app.chronotif.core.Attention

/** The last-check watermark and a handful of settings. Excluded apps live in [Exclusions]. */
object Prefs {
    const val LOCK_OFF = 0
    const val LOCK_WHEN_NEW = 1
    const val LOCK_ALWAYS = 2

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("chronotif", Context.MODE_PRIVATE)
    }

    /** Wall-clock time up to which the user has seen notifications. */
    val lastCheck: Long get() = sp.getLong("last_check", 0L)

    fun markChecked(seenUpTo: Long) {
        val next = Attention.advance(lastCheck, seenUpTo)
        if (next != lastCheck) {
            sp.edit().putLong("last_check", next).apply()
            NotifStore.changed()
        }
    }

    /** Off withholds the edge handle and lock-screen view without touching their settings. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** The status-bar swipe; it also needs Chronotif's accessibility service turned on. */
    var statusSwipeEnabled: Boolean
        get() = sp.getBoolean("status_swipe_enabled", true)
        set(v) = sp.edit().putBoolean("status_swipe_enabled", v).apply()

    var handleEnabled: Boolean
        get() = sp.getBoolean("handle_enabled", true)
        set(v) = sp.edit().putBoolean("handle_enabled", v).apply()

    var handleRight: Boolean
        get() = sp.getBoolean("handle_right", true)
        set(v) = sp.edit().putBoolean("handle_right", v).apply()

    /** Vertical position of the handle as a fraction of screen height. */
    var handleY: Float
        get() = sp.getFloat("handle_y", 0.22f)
        set(v) = sp.edit().putFloat("handle_y", v).apply()

    var handleHideLandscape: Boolean
        get() = sp.getBoolean("handle_hide_landscape", true)
        set(v) = sp.edit().putBoolean("handle_hide_landscape", v).apply()

    var lockMode: Int
        get() = sp.getInt("lock_mode", LOCK_WHEN_NEW)
        set(v) = sp.edit().putInt("lock_mode", v).apply()

    var lockHideContent: Boolean
        get() = sp.getBoolean("lock_hide_content", false)
        set(v) = sp.edit().putBoolean("lock_hide_content", v).apply()

    // --- Panel header. Android decides what the time and date say; these choose what shows,
    // where and how big (ADR 0005).

    const val DATE_ALIGN_TOP = 0
    const val DATE_ALIGN_CENTER = 1
    const val DATE_ALIGN_BOTTOM = 2
    const val CLOCK_SIZE_MIN = 32
    const val CLOCK_SIZE_MAX = 64

    var headerClock: Boolean
        get() = sp.getBoolean("header_clock", true)
        set(v) = sp.edit().putBoolean("header_clock", v).apply()

    /** In sp, so it still grows with the system font size. */
    var headerClockSize: Int
        get() = sp.getInt("header_clock_size", 48).coerceIn(CLOCK_SIZE_MIN, CLOCK_SIZE_MAX)
        set(v) = sp.edit().putInt("header_clock_size", v.coerceIn(CLOCK_SIZE_MIN, CLOCK_SIZE_MAX)).apply()

    /** The date beside the clock instead of under it. */
    var headerDateBeside: Boolean
        get() = sp.getBoolean("header_date_beside", false)
        set(v) = sp.edit().putBoolean("header_date_beside", v).apply()

    /** How the date lines up with the clock beside it. */
    var headerDateAlign: Int
        get() = sp.getInt("header_date_align", DATE_ALIGN_BOTTOM)
        set(v) = sp.edit().putInt("header_date_align", v).apply()

    var headerWeekday: Boolean
        get() = sp.getBoolean("header_weekday", true)
        set(v) = sp.edit().putBoolean("header_weekday", v).apply()

    var headerYear: Boolean
        get() = sp.getBoolean("header_year", false)
        set(v) = sp.edit().putBoolean("header_year", v).apply()

    var headerShortNames: Boolean
        get() = sp.getBoolean("header_short_names", false)
        set(v) = sp.edit().putBoolean("header_short_names", v).apply()

    /** Opt-in: check GitHub Releases daily and install newer versions. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", false)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    /** Nightly APKs opt into nightly updates on first install; existing choices persist. */
    var nightlyUpdates: Boolean
        get() = sp.getBoolean("nightly_updates", BuildConfig.NIGHTLY)
        set(v) = sp.edit().putBoolean("nightly_updates", v).apply()

    /** A version that failed to install; automatic checks skip it until a manual retry. */
    var updateFailedVersion: String?
        get() = sp.getString("update_failed_version", null)
        set(v) = sp.edit().putString("update_failed_version", v).apply()
}
