// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.SharedPreferences
import app.chronotif.core.Attention

/**
 * What's kept across restarts: the settings, the last-check watermark, when apps last notified,
 * snoozes, currency rates, and Usage data waiting to be sent.
 */
object Prefs {
    const val SHAPE_TAB = 0
    const val SHAPE_HALF_SMALL = 1
    const val SHAPE_HALF_LARGE = 2
    const val SHAPE_CIRCLE = 3
    /** A thin line in a wider, unseen touch area. */
    const val SHAPE_LINE = 4
    const val COLOUR_GREY = 0
    const val COLOUR_WHITE = 1
    const val COLOUR_BLACK = 2
    const val NEW_ACCENT = 0
    const val NEW_BLUE = 1
    const val NEW_GREEN = 2
    const val NEW_ORANGE = 3
    const val NEW_RED = 4
    const val NEW_PURPLE = 5
    /** The handle keeps its usual colour when something is New. */
    const val NEW_UNCHANGED = 6
    const val LOOK_FROSTED = 0
    const val LOOK_SOLID = 1
    const val LOOK_COUNT = 2
    const val LOCK_OFF = 0
    const val LOCK_WHEN_NEW = 1
    const val LOCK_ALWAYS = 2

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("chronotif", Context.MODE_PRIVATE)
        notifiedMemo = null
        if (notifiedDirty) main.removeCallbacks(notifiedWrite)
        notifiedDirty = false
        keepOldDefaults(context)
    }

    /**
     * Two defaults changed in 0.2.7: the lock-screen view shows whenever there are notifications,
     * and Search's currency rates are on (ADR 0015). An install from before keeps what it had: its
     * first start after the update writes the old default into each of these it never set. A new
     * install, with nothing saved yet, gets the new ones. Runs once.
     */
    private fun keepOldDefaults(context: Context) {
        if (sp.getBoolean(DEFAULTS_SETTLED, false)) return
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            null
        }
        val updated = info != null && info.firstInstallTime != info.lastUpdateTime
        val edit = sp.edit()
        if (updated || sp.all.isNotEmpty()) {
            if (!sp.contains("lock_mode")) edit.putInt("lock_mode", LOCK_WHEN_NEW)
            if (!sp.contains("currency_rates")) edit.putBoolean("currency_rates", false)
        }
        edit.putBoolean(DEFAULTS_SETTLED, true).apply()
    }

    private const val DEFAULTS_SETTLED = "defaults_0_2_7"

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

    /** Swiping a card sideways clears it; off, cards clear from their Preview or a section's Clear. */
    var swipeToClear: Boolean
        get() = sp.getBoolean("swipe_to_clear", true)
        set(v) = sp.edit().putBoolean("swipe_to_clear", v).apply()

    /**
     * Notifications snoozed from Chronotif, as "until<TAB>key", kept across restarts: when Android
     * posts one again it has come back from its snooze and counts as New (ADR 0010).
     */
    private fun snoozes(): Set<String> = sp.getStringSet("snoozed", null).orEmpty()

    fun snoozed(key: String, until: Long) {
        val kept = snoozes().filterNot { it.substringAfter('\t') == key } + "$until\t$key"
        sp.edit().putStringSet("snoozed", kept.toSet()).apply()
    }

    /**
     * Whether [key] was snoozed, forgetting it. A snooze whose notification the app removed in the
     * meantime never comes back; it's dropped a day after it would have.
     */
    fun unsnooze(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val all = snoozes()
        if (all.isEmpty()) return false
        var found = false
        val kept = all.filter { s ->
            val k = s.substringAfter('\t')
            val until = s.substringBefore('\t').toLongOrNull() ?: 0L
            if (k == key) found = true
            k != key && until + 86_400_000L > now
        }.toSet()
        if (kept != all) sp.edit().putStringSet("snoozed", kept).apply()
        return found
    }

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

    /** What's new: the release notes already shown, as [app.chronotif.core.WhatsNew.key]s; null before any. */
    var whatsNewSeen: Set<String>?
        get() = sp.getStringSet("whats_new_seen", null)
        set(v) = sp.edit().putStringSet("whats_new_seen", v).apply()

    /** What's new: the version code last looked at, so the notes are read once per update. */
    var whatsNewCode: Long
        get() = sp.getLong("whats_new_code", 0L)
        set(v) = sp.edit().putLong("whats_new_code", v).apply()

    /** The name of the settings section last open, reopened next time unless a permission is missing. */
    var settingsSection: String?
        get() = sp.getString("settings_section_name", null)
        set(v) = sp.edit().putString("settings_section_name", v).apply()

    /** Settings in light or dark: one of the THEME_ constants. */
    var settingsTheme: Int
        get() = sp.getInt("settings_theme", THEME_SYSTEM)
        set(v) = sp.edit().putInt("settings_theme", v).apply()

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    /** The edge handle's shape: one of the SHAPE_ constants. */
    var handleShape: Int
        get() = sp.getInt("handle_shape", SHAPE_HALF_SMALL)
        set(v) = sp.edit().putInt("handle_shape", v).apply()

    /** The edge handle's usual colour: one of the COLOUR_ constants. */
    var handleColour: Int
        get() = sp.getInt("handle_colour", COLOUR_GREY)
        set(v) = sp.edit().putInt("handle_colour", v).apply()

    /** The edge handle's colour while anything is New: one of the NEW_ constants. */
    var handleNewColour: Int
        get() = sp.getInt("handle_new_colour", NEW_ACCENT)
        set(v) = sp.edit().putInt("handle_new_colour", v).apply()

    /** The edge handle's style: one of the LOOK_ constants (subtle, bold, subtle with the New count). */
    var handleLook: Int
        get() = sp.getInt("handle_look", LOOK_FROSTED)
        set(v) = sp.edit().putInt("handle_look", v).apply()

    var handleHideLandscape: Boolean
        get() = sp.getBoolean("handle_hide_landscape", true)
        set(v) = sp.edit().putBoolean("handle_hide_landscape", v).apply()

    /** When the lock-screen view shows. Whenever there are notifications, unless the install predates 0.2.7. */
    var lockMode: Int
        get() = sp.getInt("lock_mode", LOCK_ALWAYS)
        set(v) = sp.edit().putInt("lock_mode", v).apply()

    var lockHideContent: Boolean
        get() = sp.getBoolean("lock_hide_content", false)
        set(v) = sp.edit().putBoolean("lock_hide_content", v).apply()

    /** Double-tapping empty space on the lock-screen view puts the display to sleep (ADR 0014). */
    var lockDoubleTapSleep: Boolean
        get() = sp.getBoolean("lock_double_tap_sleep", true)
        set(v) = sp.edit().putBoolean("lock_double_tap_sleep", v).apply()

    /**
     * The Lock-screen shortcuts, left and right. Flashlight and Camera unless changed, older
     * installs included: the lock-screen view covered the stock lock screen's own shortcuts.
     */
    var lockShortcutLeft: app.chronotif.core.LockShortcut
        get() = app.chronotif.core.LockShortcut.of(sp.getString("lock_shortcut_left", null), app.chronotif.core.LockShortcut.DEFAULT_LEFT)
        set(v) = sp.edit().putString("lock_shortcut_left", v.key).apply()

    var lockShortcutRight: app.chronotif.core.LockShortcut
        get() = app.chronotif.core.LockShortcut.of(sp.getString("lock_shortcut_right", null), app.chronotif.core.LockShortcut.DEFAULT_RIGHT)
        set(v) = sp.edit().putString("lock_shortcut_right", v.key).apply()

    /** The app a slot set to An app opens, as [Launchables.key]; never sent anywhere. */
    var lockShortcutLeftApp: String?
        get() = sp.getString("lock_shortcut_left_app", null)
        set(v) = sp.edit().putString("lock_shortcut_left_app", v).apply()

    var lockShortcutRightApp: String?
        get() = sp.getString("lock_shortcut_right_app", null)
        set(v) = sp.edit().putString("lock_shortcut_right_app", v).apply()

    /** Whether the Chronotif QR scanner tile is in Quick Settings, as far as Android has told the tile. */
    var qrTileAdded: Boolean
        get() = sp.getBoolean("qr_tile_added", false)
        set(v) = sp.edit().putBoolean("qr_tile_added", v).apply()

    /** Where the person's touch put their under-display fingerprint sensor's top edge, as a
     *  fraction of the screen's height; 0 when it hasn't been fitted (see FingerprintSpot). */
    var fingerprintTop: Float
        get() = sp.getFloat("fingerprint_top", 0f)
        set(v) = sp.edit().putFloat("fingerprint_top", v).apply()

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

    /**
     * Search converts currencies with the ECB's daily rates, downloaded when a currency is typed.
     * On, unless the install predates 0.2.7, when it was opt-in (ADR 0015).
     */
    var currencyRates: Boolean
        get() = sp.getBoolean("currency_rates", true)
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

    /** The Tutorial part and step the user left at, as "PART:step", or null. */
    var tutorialPlace: String?
        get() = sp.getString("tutorial_place", null)
        set(v) = sp.edit().putString("tutorial_place", v).apply()

    /** Tutorial parts the user has finished, by [Tutorial.Part] name. */
    var tutorialDone: Set<String>
        get() = sp.getStringSet("tutorial_done", null).orEmpty().toSet()
        set(v) = sp.edit().putStringSet("tutorial_done", v).apply()

    /** Search checklist steps the user said Not now to, by [SearchSetup.Step] name. */
    var searchSetupSkipped: Set<String>
        get() = sp.getStringSet("search_setup_skipped", null).orEmpty().toSet()
        set(v) = sp.edit().putStringSet("search_setup_skipped", v).apply()

    /** Whether the Search tile is in Quick Settings, as far as Android has told the tile. */
    var searchTileAdded: Boolean
        get() = sp.getBoolean("search_tile_added", false)
        set(v) = sp.edit().putBoolean("search_tile_added", v).apply()

    /**
     * When each app last notified, for Search's suggestion row: app id (package|user) to
     * arrival time. Times only, never content, and only the most recent few apps are kept.
     */
    val lastNotified: Map<String, Long> get() = HashMap(notifiedNow())

    /**
     * [lastNotified] as it stands. Every arrival updates it here, and [flushNotified] writes it out
     * later, so a burst of notifications doesn't parse and rewrite the preferences file each time.
     * A process killed before the write loses only those last few seconds of recency.
     */
    private var notifiedMemo: MutableMap<String, Long>? = null
    private var notifiedDirty = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val notifiedWrite = Runnable { flushNotified() }

    private fun notifiedNow(): MutableMap<String, Long> = notifiedMemo
        ?: sp.getString("last_notified", null).orEmpty().split(';').mapNotNull { pair ->
            val i = pair.lastIndexOf('=')
            if (i <= 0) null else pair.substring(i + 1).toLongOrNull()?.let { pair.substring(0, i) to it }
        }.toMap(HashMap()).also { notifiedMemo = it }

    /** Called on the main thread, as each notification arrives. */
    fun notified(appId: String, at: Long) {
        val all = notifiedNow()
        if ((all[appId] ?: 0L) >= at) return
        all[appId] = at
        if (all.size > LAST_NOTIFIED_KEPT) all.remove(all.minBy { it.value }.key)
        if (!notifiedDirty) {
            notifiedDirty = true
            main.postDelayed(notifiedWrite, NOTIFIED_WRITE_DELAY_MS)
        }
    }

    /** Writes [lastNotified] out now, if it changed: a while after an arrival, when the screen goes off, and when the listener stops. */
    fun flushNotified() {
        if (!notifiedDirty) return
        notifiedDirty = false
        main.removeCallbacks(notifiedWrite)
        val kept = notifiedNow().entries.sortedByDescending { it.value }
        sp.edit().putString("last_notified", kept.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    /** How long arrivals gather before [lastNotified] is written. */
    private const val NOTIFIED_WRITE_DELAY_MS = 10_000L

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

    // --- Usage data (ADR 0016) ---

    /** Usage data is sent; on unless turned off. */
    var usageData: Boolean
        get() = sp.getBoolean("usage_data", true)
        set(v) = sp.edit().putBoolean("usage_data", v).apply()

    /** The notice that Usage data is on has been answered in settings. */
    var usageNoticed: Boolean
        get() = sp.getBoolean("usage_noticed", false)
        set(v) = sp.edit().putBoolean("usage_noticed", v).apply()

    /** The random install ID events carry; null while Usage data is off. */
    var usageId: String?
        get() = sp.getString("usage_id", null)
        set(v) = sp.edit().putString("usage_id", v).apply()

    /** The day (epoch day) the waiting counts began. */
    var usageDay: Long
        get() = sp.getLong("usage_day", 0L)
        set(v) = sp.edit().putLong("usage_day", v).apply()

    /** The day's counts, in [app.chronotif.core.Usage.encodeCounts]'s form. */
    val usageCounts: String? get() = sp.getString("usage_counts", null)

    /** The day's timed Panel opens, in [app.chronotif.core.Usage.encodeSpeeds]'s form. */
    val usageSpeeds: String? get() = sp.getString("usage_speeds", null)

    /** Crashes waiting to be sent. */
    val usageCrashes: String? get() = sp.getString("usage_crashes", null)

    /** The counts and speeds kept in memory; null leaves that one as it is. */
    fun keepUsage(counts: String?, speeds: String?) {
        sp.edit().apply {
            if (counts != null) putString("usage_counts", counts)
            if (speeds != null) putString("usage_speeds", speeds)
        }.apply()
    }

    /** Written at once with the counts and speeds kept in memory: it's called as the process ends. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun keepUsageCrashes(v: String, counts: String?, speeds: String?) {
        sp.edit().apply {
            putString("usage_crashes", v)
            if (counts != null) putString("usage_counts", counts)
            if (speeds != null) putString("usage_speeds", speeds)
        }.commit()
    }

    /** A batch built and not yet taken by PostHog: its exact request body, sent again unchanged. */
    val usageBatch: String? get() = sp.getString("usage_batch", null)

    /**
     * Keeps [batch] and takes what went into it out of what's waiting, in one write that's on disk
     * before the batch is sent: a lost answer then resends this batch, never a second one.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun keepUsageBatch(batch: String?, day: Long, counts: String, speeds: String, crashes: String) {
        sp.edit().putString("usage_batch", batch).putLong("usage_day", day).putString("usage_counts", counts)
            .putString("usage_speeds", speeds).putString("usage_crashes", crashes).putBoolean("usage_on_pending", false)
            .commit()
    }

    /** PostHog has [batch], or never will; a different batch kept since stays. */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun dropUsageBatch(batch: String) {
        if (usageBatch == batch) sp.edit().remove("usage_batch").commit()
    }

    /** The newest app exit already looked at, so each ANR is counted once. */
    var usageExitsSeen: Long
        get() = sp.getLong("usage_exits_seen", 0L)
        set(v) = sp.edit().putLong("usage_exits_seen", v).apply()

    /** A new install of Usage data hasn't sent its first event yet. */
    var usageOnPending: Boolean
        get() = sp.getBoolean("usage_on_pending", false)
        set(v) = sp.edit().putBoolean("usage_on_pending", v).apply()

    /** Forgets the install ID and everything waiting to be sent. */
    fun clearUsage() {
        sp.edit().remove("usage_id").remove("usage_day").remove("usage_counts").remove("usage_speeds")
            .remove("usage_crashes").remove("usage_exits_seen").remove("usage_on_pending").remove("usage_batch").apply()
    }

    /** A version that failed to install; automatic checks skip it until a manual retry. */
    var updateFailedVersion: String?
        get() = sp.getString("update_failed_version", null)
        set(v) = sp.edit().putString("update_failed_version", v).apply()
}
