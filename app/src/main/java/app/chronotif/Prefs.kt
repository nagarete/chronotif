// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.SharedPreferences
import app.chronotif.core.Attention

/** The only persisted state: the last-check watermark, when apps last notified, and a handful of settings. */
object Prefs {
    const val LOOK_FROSTED = 0
    const val LOOK_SOLID = 1
    const val LOOK_COUNT = 2
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

    /**
     * The edge handle's look, while the three candidates are tried out. Only the debug build
     * offers the choice; it goes once one is picked.
     */
    var handleLook: Int
        get() = sp.getInt("handle_look", LOOK_FROSTED)
        set(v) = sp.edit().putInt("handle_look", v).apply()

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

    /** A gear at the header's end that opens Android's Settings. */
    var headerSettings: Boolean
        get() = sp.getBoolean("header_settings", true)
        set(v) = sp.edit().putBoolean("header_settings", v).apply()

    /** Search: pulled down from the top of the panel or lock-screen view (ADR 0008). */
    var pullDownSearch: Boolean
        get() = sp.getBoolean("app_search", true)
        set(v) = sp.edit().putBoolean("app_search", v).apply()

    /** Search's web row: an index into [app.chronotif.core.WebQuery.Engine], Google by default. */
    var searchEngine: Int
        get() = sp.getInt("search_engine", 0)
        set(v) = sp.edit().putInt("search_engine", v).apply()

    /** Search finds contacts. Off until turned on, which asks for the Contacts permission. */
    var searchContacts: Boolean
        get() = sp.getBoolean("search_contacts", false)
        set(v) = sp.edit().putBoolean("search_contacts", v).apply()

    /** Search's call buttons place calls, WhatsApp ones included. Off until turned on, which asks for the Phone permission. */
    var searchCalls: Boolean
        get() = sp.getBoolean("search_calls", false)
        set(v) = sp.edit().putBoolean("search_calls", v).apply()

    /** Search converts currencies with the ECB's daily rates. Off until turned on: it's a download. */
    var currencyRates: Boolean
        get() = sp.getBoolean("currency_rates", false)
        set(v) = sp.edit().putBoolean("currency_rates", v).apply()

    /** The last downloaded rates, in [app.chronotif.core.Money.encode]'s form. */
    var exchangeRates: String?
        get() = sp.getString("exchange_rates", null)
        set(v) = sp.edit().putString("exchange_rates", v).apply()

    var exchangeRatesFetchedAt: Long
        get() = sp.getLong("exchange_rates_at", 0L)
        set(v) = sp.edit().putLong("exchange_rates_at", v).apply()

    /** Search finds calendar events. Off until turned on, which asks for the Calendar permission. */
    var searchCalendar: Boolean
        get() = sp.getBoolean("search_calendar", false)
        set(v) = sp.edit().putBoolean("search_calendar", v).apply()

    /**
     * When each app last notified, for Search's suggestion row: app id (package|user) to
     * arrival time. Times only, never content, and only the most recent few apps are kept.
     */
    val lastNotified: Map<String, Long>
        get() = sp.getString("last_notified", null).orEmpty().split(';').mapNotNull { pair ->
            val i = pair.lastIndexOf('=')
            if (i <= 0) null else pair.substring(i + 1).toLongOrNull()?.let { pair.substring(0, i) to it }
        }.toMap()

    fun notified(appId: String, at: Long) {
        val all = lastNotified
        if ((all[appId] ?: 0L) >= at) return
        val kept = (all + (appId to at)).entries.sortedByDescending { it.value }.take(LAST_NOTIFIED_KEPT)
        sp.edit().putString("last_notified", kept.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    /** Enough to fill the row when some of them can't be launched (uninstalled, no launcher entry). */
    private const val LAST_NOTIFIED_KEPT = 16

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
