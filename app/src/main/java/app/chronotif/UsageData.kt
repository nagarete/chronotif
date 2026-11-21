// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import app.chronotif.core.Usage
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Usage data (ADR 0016): anonymous counts, Panel speeds, settings and crash reports, sent to
 * PostHog's EU servers so Chronotif can be improved from how it's really used. Never anything
 * from a notification, a contact, a calendar event or Search. `docs/usage-data.md` lists every
 * field.
 *
 * On unless turned off. Only Stable and Nightly builds carry the key; the debug build sends
 * nothing. Nothing runs on a timer: the day's counts go out on the first app start or Panel close
 * of a new day, and a crash on the next one. Turning it off sends one last "turned off" event,
 * then forgets the install ID and everything waiting to be sent.
 */
// Its one context is the application's, never an activity's.
@SuppressLint("StaticFieldLeak")
object UsageData {
    private const val TAG = "UsageData"
    private const val ENDPOINT = "https://eu.i.posthog.com/batch/"
    /** After a failed send, wait this long before trying again. */
    private val RETRY_MS = TimeUnit.HOURS.toMillis(1)
    private const val MAX_CRASHES = 3
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var sending = false
    private var failedAt = 0L

    /** Whether this build can send at all. */
    val built: Boolean get() = BuildConfig.USAGE_DATA_KEY.isNotEmpty()

    private val active: Boolean get() = built && Prefs.usageData && Prefs.usageId != null

    fun init(context: Context) {
        app = context.applicationContext
        if (!built) return
        catchCrashes()
        if (!Prefs.usageData) return
        if (Prefs.usageId == null) start()
        recordExits()
        sendIfDue()
    }

    /** The setting. Off sends one last event, then forgets the install; on starts a new one. */
    fun set(on: Boolean) {
        if (on == Prefs.usageData) return
        Prefs.usageData = on
        if (!built) return
        if (on) {
            start()
            sendIfDue()
            return
        }
        val install = Prefs.usageId
        Prefs.clearUsage()
        if (install == null) return
        val event = Usage.event(install, "usage_data_off", System.currentTimeMillis(), basics())
        thread(name = "usage-data") { runCatching { post(install, listOf(event)) } }
    }

    private fun start() {
        Prefs.clearUsage()
        Prefs.usageId = UUID.randomUUID().toString()
        Prefs.usageDay = today()
        Prefs.usageOnPending = true
        // Exits from before this install was counting aren't counted.
        Prefs.usageExitsSeen = System.currentTimeMillis()
    }

    fun count(c: Usage.Count) {
        if (!active) return
        val all = Usage.decodeCounts(Prefs.usageCounts)
        Prefs.usageCounts = Usage.encodeCounts(all + (c.key to (all[c.key] ?: 0) + 1))
    }

    /** A Panel open took [ms] from the request until its cards were drawn. */
    fun panelSpeed(ms: Long, cards: Int, kept: Boolean, pulled: Boolean) {
        if (!active || ms < 0) return
        val speeds = Usage.decodeSpeeds(Prefs.usageSpeeds)
        if (speeds.size >= Usage.MAX_SPEEDS) return
        val s = Usage.Speed(System.currentTimeMillis(), ms, Usage.cardBucket(cards), kept, pulled)
        Prefs.usageSpeeds = Usage.encodeSpeeds(speeds + s)
    }

    // --- Crashes ---

    private fun catchCrashes() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { if (active) keepCrash(e) }
            before?.uncaughtException(t, e)
        }
    }

    private fun keepCrash(e: Throwable) {
        val p = Usage.crashProps(e)
        val record = listOf(System.currentTimeMillis().toString(), p["exception"], p["causes"], p["frames"])
            .joinToString(FIELD.toString())
        val kept = crashes().takeLast(MAX_CRASHES - 1) + record
        // Written now: the process is about to end.
        Prefs.keepUsageCrashes(kept.joinToString(RECORD.toString()))
    }

    private fun crashes(): List<String> = Prefs.usageCrashes.orEmpty().split(RECORD).filter { it.isNotEmpty() }

    private fun crashEvent(install: String, record: String, seq: Int): Usage.Event? {
        val f = record.split(FIELD)
        if (f.size != 4) return null
        val at = f[0].toLongOrNull() ?: return null
        return Usage.event(install, "crash", at, mapOf("exception" to f[1], "causes" to f[2], "frames" to f[3]), seq)
    }

    /** ANRs and native crashes, which no handler sees, from Android's record of how the app ended. */
    private fun recordExits() {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 16) }.getOrNull() ?: return
        val since = Prefs.usageExitsSeen
        var newest = since
        for (x in exits) {
            if (x.timestamp <= since) continue
            newest = maxOf(newest, x.timestamp)
            if (x.reason == ApplicationExitInfo.REASON_ANR) count(Usage.Count.ANR)
            else if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) count(Usage.Count.NATIVE_CRASH)
        }
        if (newest != since) Prefs.usageExitsSeen = newest
    }

    // --- Sending ---

    /** Sends what's waiting, if a day has ended, a crash is waiting, or the install is new. */
    fun sendIfDue() {
        if (!active || sending) return
        val install = Prefs.usageId ?: return
        val now = System.currentTimeMillis()
        if (now - failedAt < RETRY_MS) return
        val day = today()
        val newDay = Prefs.usageDay != day
        val crashes = crashes()
        val on = Prefs.usageOnPending
        if (!newDay && crashes.isEmpty() && !on) return
        val counts = if (newDay) Usage.decodeCounts(Prefs.usageCounts) else emptyMap()
        val speeds = if (newDay) Usage.decodeSpeeds(Prefs.usageSpeeds) else emptyList()
        val settings = if (newDay || on) settings() else emptyMap()
        sending = true
        thread(name = "usage-data") {
            val ok = runCatching {
                val device = device()
                val events = ArrayList<Usage.Event>()
                if (on) events += Usage.event(install, "usage_data_on", now, device + settings)
                if (newDay) {
                    val days = (day - Prefs.usageDay).coerceAtLeast(1)
                    val n = counts.mapKeys { "n_${it.key}" }
                    events += Usage.event(install, "daily", now, device + settings + n + ("days" to days))
                    speeds.forEachIndexed { i, s ->
                        events += Usage.event(install, "panel_speed", s.at, basics() + mapOf(
                            "ms" to s.ms, "cards" to s.cards, "kept" to s.kept, "pulled" to s.pulled,
                            "\$device_model" to Build.MODEL,
                        ), i)
                    }
                }
                crashes.forEachIndexed { i, r -> crashEvent(install, r, i)?.let { events += it.withProps(device) } }
                post(install, events)
            }.onFailure { Log.w(TAG, "Couldn't send usage data", it) }.getOrDefault(false)
            main.post {
                sending = false
                if (!ok) {
                    failedAt = System.currentTimeMillis()
                    return@post
                }
                failedAt = 0L
                // The install may have been turned off or restarted while this was on its way.
                if (Prefs.usageId != install) return@post
                if (on) Prefs.usageOnPending = false
                if (newDay) {
                    Prefs.usageDay = day
                    Prefs.usageCounts = Usage.encodeCounts(Usage.minus(Usage.decodeCounts(Prefs.usageCounts), counts))
                    Prefs.usageSpeeds = Usage.encodeSpeeds(Usage.decodeSpeeds(Prefs.usageSpeeds).drop(speeds.size))
                }
                if (crashes.isNotEmpty()) {
                    Prefs.keepUsageCrashes(crashes().drop(crashes.size).joinToString(RECORD.toString()))
                }
            }
        }
    }

    private fun Usage.Event.withProps(more: Map<String, Any?>) = Usage.Event(name, at, more + props, uuid)

    private fun post(install: String, events: List<Usage.Event>): Boolean {
        if (events.isEmpty()) return true
        val body = Usage.batch(BuildConfig.USAGE_DATA_KEY, install, events).toByteArray()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        c.setFixedLengthStreamingMode(body.size)
        return try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) Log.w(TAG, "Usage data: HTTP $code")
            code in 200..299
        } finally {
            c.disconnect()
        }
    }

    private fun today() = LocalDate.now().toEpochDay()

    // --- What's sent with events ---

    private fun basics(): Map<String, Any?> = mapOf(
        "\$app_version" to BuildConfig.VERSION_NAME,
        "channel" to if (BuildConfig.NIGHTLY) "nightly" else "stable",
    )

    /** The phone: Android's version, maker, model, the maker's system and the country. Reads system properties, so off the main thread. */
    private fun device(): Map<String, Any?> {
        val country = phoneCountries(app).firstOrNull { it.length == 2 }?.uppercase()
        return basics() + mapOf(
            "\$os" to "Android",
            "\$os_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "\$device_manufacturer" to Build.MANUFACTURER,
            "\$device_model" to Build.MODEL,
            "system" to Usage.system(::systemProperty),
            "country" to country,
            "\$geoip_country_code" to country,
        )
    }

    private fun systemProperty(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        out.ifEmpty { null }
    }.getOrNull()

    /** Chronotif's settings and permissions, as they are now. */
    private fun settings(): Map<String, Any?> = mapOf(
        "set_enabled" to Prefs.enabled,
        "set_swipe_to_clear" to Prefs.swipeToClear,
        "set_status_swipe" to Prefs.statusSwipeEnabled,
        "set_handle" to Prefs.handleEnabled,
        "set_handle_right" to Prefs.handleRight,
        "set_handle_shape" to Prefs.handleShape,
        "set_handle_colour" to Prefs.handleColour,
        "set_handle_new_colour" to Prefs.handleNewColour,
        "set_handle_look" to Prefs.handleLook,
        "set_handle_hide_landscape" to Prefs.handleHideLandscape,
        "set_lock_mode" to Prefs.lockMode,
        "set_lock_hide_content" to Prefs.lockHideContent,
        "set_lock_double_tap" to Prefs.lockDoubleTapSleep,
        "set_header_clock" to Prefs.headerClock,
        "set_header_clock_size" to Prefs.headerClockSize,
        "set_header_date_beside" to Prefs.headerDateBeside,
        "set_header_date_align" to Prefs.headerDateAlign,
        "set_header_weekday" to Prefs.headerWeekday,
        "set_header_year" to Prefs.headerYear,
        "set_header_short_names" to Prefs.headerShortNames,
        "set_header_settings" to Prefs.headerSettings,
        "set_pull_down_search" to Prefs.pullDownSearch,
        "set_search_engine" to Prefs.searchEngine,
        "set_search_contacts" to Prefs.searchContacts,
        "set_search_calls" to Prefs.searchCalls,
        "set_search_calendar" to Prefs.searchCalendar,
        "set_currency_rates" to Prefs.currencyRates,
        "set_search_tile" to Prefs.searchTileAdded,
        "set_auto_update" to Prefs.autoUpdate,
        "set_nightly_updates" to Prefs.nightlyUpdates,
        "set_settings_theme" to Prefs.settingsTheme,
        "tutorial_parts_done" to Prefs.tutorialDone.size,
        "perm_listener" to ListenerService.isEnabled(app),
        "perm_overlay" to Settings.canDrawOverlays(app),
        "perm_status_swipe" to (StatusBarSwipe.instance != null),
    )
}
