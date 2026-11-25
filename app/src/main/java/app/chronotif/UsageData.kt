// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import app.chronotif.core.Usage
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Usage data (ADR 0016): anonymous counts, Panel speeds, settings and crash reports, sent to
 * PostHog's EU servers so Chronotif can be improved from how it's really used. Never anything
 * from a notification, a contact, a calendar event or Search. `docs/usage-data.md` lists every
 * field.
 *
 * On unless turned off. Only Stable and Nightly builds carry the key; the debug build sends
 * nothing. Nothing runs on a timer: the day's counts go out on the first app start or Panel close
 * of a new day, and a crash on the next one. A batch is kept until PostHog has it, and a failed
 * send tries that same batch again. Turning it off sends one last "turned off" event, then
 * forgets the install ID and everything waiting to be sent.
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

    private lateinit var app: Context

    /**
     * Reading system services, writing counts and sending all happen here, one at a time, so a
     * send can't overtake the "turned off" event queued after it. The thread ends when idle.
     */
    internal var worker: Executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "usage-data")
    }

    /** PostHog's project key; empty in the debug build. Tests set one. */
    internal var key: String = BuildConfig.USAGE_DATA_KEY

    /** Posts a batch's request body and returns the HTTP status. Tests replace it. */
    internal var transport: (String) -> Int = ::post

    /** When the last send failed; only the worker reads or writes it. */
    internal var failedAt = 0L

    // Counts and speeds are kept in memory and written at [flush], [sendIfDue] and a crash, not
    // on every count. Guarded by [lock], as are the writes that take them into a batch.
    private val lock = Any()
    private var counts: HashMap<String, Int>? = null
    private var speeds: ArrayList<Usage.Speed>? = null
    private var unsaved = false

    /** Whether this build can send at all. */
    val built: Boolean get() = key.isNotEmpty()

    private val active: Boolean get() = built && Prefs.usageData && Prefs.usageId != null

    fun init(context: Context) {
        app = context.applicationContext
        if (!built) return
        catchCrashes()
        // Android's exit records and the settings a batch carries are binder calls: off the start path.
        worker.execute {
            if (!Prefs.usageData) return@execute
            if (Prefs.usageId == null) start()
            recordExits()
            save()
            sendDue()
        }
    }

    /** The setting. Off sends one last event, then forgets the install; on starts a new one. */
    fun set(on: Boolean) {
        if (on == Prefs.usageData) return
        Prefs.usageData = on
        if (!built) return
        if (on) {
            worker.execute {
                if (Prefs.usageId == null) start()
                sendDue()
            }
            return
        }
        val install = Prefs.usageId
        // Under the lock, so a batch being built now is either kept and then forgotten here, or not kept.
        synchronized(lock) {
            Prefs.clearUsage()
            forget()
        }
        if (install == null) return
        val event = Usage.event(install, "usage_data_off", System.currentTimeMillis(), basics())
        worker.execute {
            runCatching { transport(Usage.batch(key, install, listOf(event))) }
                .onFailure { Log.w(TAG, "Couldn't send usage data", it) }
        }
    }

    private fun start() {
        synchronized(lock) {
            Prefs.clearUsage()
            forget()
        }
        Prefs.usageId = UUID.randomUUID().toString()
        Prefs.usageDay = today()
        Prefs.usageOnPending = true
        // Exits from before this install was counting aren't counted.
        Prefs.usageExitsSeen = System.currentTimeMillis()
    }

    private fun forget() {
        counts = null
        speeds = null
        unsaved = false
    }

    fun count(c: Usage.Count) {
        if (!active) return
        synchronized(lock) {
            val all = keptCounts()
            all[c.key] = (all[c.key] ?: 0) + 1
            unsaved = true
        }
    }

    /** A Panel open took [ms] from the request until its cards were drawn. */
    fun panelSpeed(ms: Long, cards: Int, kept: Boolean, pulled: Boolean) {
        if (!active || ms < 0) return
        synchronized(lock) {
            val all = keptSpeeds()
            if (all.size >= Usage.MAX_SPEEDS) return
            all += Usage.Speed(System.currentTimeMillis(), ms, Usage.cardBucket(cards), kept, pulled)
            unsaved = true
        }
    }

    /**
     * Writes the counts kept in memory. Called when the lock screen closes and the screen turns
     * off (the Panel's close goes through [sendIfDue]), so a killed process loses only what
     * happened since.
     */
    fun flush() {
        if (built) worker.execute(::save)
    }

    private fun save() {
        synchronized(lock) {
            if (!unsaved) return
            Prefs.keepUsage(counts?.let(Usage::encodeCounts), speeds?.let(Usage::encodeSpeeds))
            unsaved = false
        }
    }

    // Read from prefs on first use after a start; callers hold [lock].
    private fun keptCounts() = counts ?: HashMap(Usage.decodeCounts(Prefs.usageCounts)).also { counts = it }
    private fun keptSpeeds() = speeds ?: ArrayList(Usage.decodeSpeeds(Prefs.usageSpeeds)).also { speeds = it }

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
        synchronized(lock) {
            val kept = crashes().takeLast(MAX_CRASHES - 1) + record
            // Written now, with the counts in memory: the process is about to end.
            Prefs.keepUsageCrashes(
                kept.joinToString(RECORD.toString()),
                counts?.let(Usage::encodeCounts), speeds?.let(Usage::encodeSpeeds),
            )
            unsaved = false
        }
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

    /**
     * Writes the counts kept in memory, then sends what's waiting if a day has ended, a crash is
     * waiting, the install is new, or a batch is still waiting for PostHog.
     */
    fun sendIfDue() {
        if (!built) return
        worker.execute {
            save()
            sendDue()
        }
    }

    // On the worker.
    private fun sendDue() {
        if (!active) return
        val install = Prefs.usageId ?: return
        val now = System.currentTimeMillis()
        if (now - failedAt < RETRY_MS) return
        val body = Prefs.usageBatch ?: build(install) ?: return
        // Turned off while this was being built: nothing more goes out for this install.
        if (Prefs.usageId != install) return
        val code = runCatching { transport(body) }.onFailure { Log.w(TAG, "Couldn't send usage data", it) }.getOrDefault(0)
        when {
            code in 200..299 -> {
                failedAt = 0L
                synchronized(lock) { Prefs.dropUsageBatch(body) }
            }
            // PostHog will never take this batch (a bad key or body); keeping it would hold back every later one.
            code in 400..499 && code != 408 && code != 429 -> {
                Log.w(TAG, "Usage data: HTTP $code, batch dropped")
                failedAt = System.currentTimeMillis()
                synchronized(lock) { Prefs.dropUsageBatch(body) }
            }
            else -> {
                if (code != 0) Log.w(TAG, "Usage data: HTTP $code")
                failedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Builds what's due into one batch and keeps it, taking its counts, speeds and crashes out of
     * what's waiting in the same write. A retry then sends exactly this batch, with the same event
     * IDs, and counts made since wait for the next one. Null when nothing is due.
     */
    private fun build(install: String): String? {
        val now = System.currentTimeMillis()
        val day = today()
        val newDay = Prefs.usageDay != day
        val on = Prefs.usageOnPending
        if (!newDay && !on && crashes().isEmpty()) return null
        // Binder calls and system properties, read before taking the lock that counting waits on.
        val device = device()
        val settings = if (newDay || on) settings() else emptyMap()
        synchronized(lock) {
            if (Prefs.usageId != install) return null
            val crashes = crashes()
            val events = ArrayList<Usage.Event>()
            if (on) events += Usage.event(install, "usage_data_on", now, device + settings)
            if (newDay) {
                val days = (day - Prefs.usageDay).coerceAtLeast(1)
                val n = keptCounts().mapKeys { "n_${it.key}" }
                events += Usage.event(install, "daily", now, device + settings + n + ("days" to days))
                keptSpeeds().forEachIndexed { i, s ->
                    events += Usage.event(install, "panel_speed", s.at, basics() + mapOf(
                        "ms" to s.ms, "cards" to s.cards, "kept" to s.kept, "pulled" to s.pulled,
                        "\$device_model" to Build.MODEL,
                    ), i)
                }
                counts = HashMap()
                speeds = ArrayList()
            }
            crashes.forEachIndexed { i, r -> crashEvent(install, r, i)?.let { events += it.withProps(device) } }
            // Only unreadable crash records were due: they're dropped, and nothing is sent.
            val body = if (events.isEmpty()) null else Usage.batch(key, install, events)
            Prefs.keepUsageBatch(
                body, day,
                Usage.encodeCounts(keptCounts()), Usage.encodeSpeeds(keptSpeeds()),
                crashes().drop(crashes.size).joinToString(RECORD.toString()),
            )
            unsaved = false
            return body
        }
    }

    private fun Usage.Event.withProps(more: Map<String, Any?>) = Usage.Event(name, at, more + props, uuid)

    private fun post(body: String): Int {
        val bytes = body.toByteArray()
        val c = URL(ENDPOINT).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        c.setFixedLengthStreamingMode(bytes.size)
        return try {
            c.outputStream.use { it.write(bytes) }
            c.responseCode
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

    internal fun systemProperty(name: String): String? = runCatching {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        // Waited for before reading: a read would block for as long as getprop hangs. One
        // property's value fits in the pipe's buffer, so getprop can finish before it's read.
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            Log.w(TAG, "getprop timed out")
            return@runCatching null
        }
        p.inputStream.bufferedReader().use { it.readText() }.trim().ifEmpty { null }
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
        "set_lock_shortcut_left" to Prefs.lockShortcutLeft.key,
        "set_lock_shortcut_right" to Prefs.lockShortcutRight.key,
        "set_qr_tile" to Prefs.qrTileAdded,
        "has_google_lens" to GoogleLens.installed(app),
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
