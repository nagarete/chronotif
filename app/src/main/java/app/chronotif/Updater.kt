// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.chronotif.core.Version
import app.chronotif.ui.SetupActivity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Auto-update from GitHub Releases (ADR 0025): it finds the newest release tag on github.com and
 * downloads the APK plus its `.sha256`. On unless turned off. With it off, the daily check only
 * posts Update available, once per build, and installing takes a tap.
 *
 * It reads github.com pages, not api.github.com, whose anonymous quota of 60 requests an hour is
 * shared by everyone behind the same IP address (ADR 0006). CI names the assets after the tag, so
 * the tag alone gives both download URLs.
 *
 * Installs go through PackageInstaller. On Android 12+ a self-update can be silent once
 * "Install unknown apps" is allowed for Chronotif; otherwise the system asks for confirmation.
 * Android itself refuses an APK not signed with the same key as the installed app.
 */
object Updater {
    private const val REPO = "https://github.com/nagarete/chronotif"
    private const val MAX_APK_BYTES = 50L shl 20
    private const val JOB_ID = 1
    /** The one-off install that waits for Wi-Fi after a daily check found an update on mobile data. */
    private const val INSTALL_JOB_ID = 2
    /** Raised when the job's constraints change, so jobs scheduled by an older version are rescheduled. */
    private const val JOB_VERSION = 3
    private const val KEY_JOB_VERSION = "version"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 1
    const val EXTRA_VERSION = "version"
    /** Opens Settings on Updates and installs the newest version, from Update available. */
    const val ACTION_INSTALL = "app.chronotif.INSTALL_UPDATE"

    class Release(val version: String, val apkUrl: String, val sumUrl: String)

    /** GitHub refused the request, most often because this IP address made too many. */
    class RateLimited : IOException("rate limited")

    /** The daily check was stopped, or auto-update was turned off while it ran. */
    private class Stopped : IOException("stopped")

    /** GitHub answered with an HTTP status other than the expected one. */
    private class HttpFailure(val code: Int) : IOException("HTTP $code")

    /** The APK didn't match its `.sha256`, or the checksum file was unreadable. */
    private class BadChecksum(message: String) : IOException(message)

    /**
     * How a check, or the install it started, ended, kept for Usage data (`docs/usage-data.md`)
     * so updates that never arrive can be told apart from ones nobody confirmed.
     */
    enum class Outcome(val key: String) {
        CURRENT("current"),
        /** An automatic check passed over a version that failed to install before. */
        SKIPPED("skipped"),
        /** Auto-update is off, so Update available was posted (or had been for this build). */
        AVAILABLE("available"),
        /** Auto-update is on, and the download waits for Wi-Fi and a battery that isn't low. */
        WAITING("waiting"),
        /** Handed to Android's installer; one of the outcomes below follows. */
        INSTALLING("installing"),
        /** Android asks the user to confirm the install. */
        CONFIRM("confirm"),
        /** The user dismissed Android's confirmation. */
        DISMISSED("dismissed"),
        INSTALLED("installed"),
        INSTALL_FAILED("install_failed"),
        RATE_LIMITED("rate_limited"),
        HTTP("http"),
        CHECKSUM("checksum"),
        /** Android stopped the daily check, most often because Wi-Fi went or the battery got low. */
        STOPPED("stopped"),
        /** No connection, a timeout, or any other failure. */
        ERROR("error"),
    }

    /** Keeps [o] for Usage data; an install's result keeps the kind of check that started it. */
    fun outcome(o: Outcome, code: Int = 0, manual: Boolean = Prefs.updateOutcomeManual) =
        Prefs.keepUpdateOutcome(o.key, code, manual)

    /** One check. Android stops the daily job's check through [stopDaily]; a manual check runs on. */
    private class Check(val manual: Boolean) {
        @Volatile var stopped = false
        @Volatile var connection: HttpURLConnection? = null

        /** Throws [Stopped] once the job is stopped. */
        fun goOn() {
            if (stopped) throw Stopped()
        }

        /** Throws [Stopped] once the job is stopped, or a daily check's download finds Auto-update off. */
        fun goOnInstalling() {
            if (stopped || (!manual && !Prefs.autoUpdate)) throw Stopped()
        }
    }

    /** True while the setup screen is visible, so install prompts open directly instead of notifying. */
    @Volatile var foreground = false

    private val running = AtomicBoolean(false)
    @Volatile private var check: Check? = null
    private val main = Handler(Looper.getMainLooper())

    // --- What Settings shows; main thread only ---

    /** The current or last check's progress, or null before a check in this process. */
    var status: String? = null
        private set

    /** A check is running; Settings disables its button. */
    val busy: Boolean get() = running.get()

    private val watchers = ArrayList<() -> Unit>()

    /** Calls [w] on the main thread whenever [status] or [busy] changes, until [unwatch]. */
    fun watch(w: () -> Unit) {
        watchers += w
    }

    fun unwatch(w: () -> Unit) {
        watchers -= w
    }

    private fun changed() = watchers.toList().forEach { it() }

    // --- Scheduling ---

    /**
     * Keeps the daily check scheduled, whether or not Auto-update is on: with it off, the check
     * still posts Update available. Cheap; called on every app start.
     */
    fun sync(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!Prefs.autoUpdate) js.cancel(INSTALL_JOB_ID)
        // A job an older version scheduled keeps its old constraints until it's replaced.
        if (js.getPendingJob(JOB_ID)?.extras?.getInt(KEY_JOB_VERSION) == JOB_VERSION) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            // The check reads a redirect or a small feed, so it runs on any network; only the
            // APK's download waits for Wi-Fi (see [waitForWifi]).
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putInt(KEY_JOB_VERSION, JOB_VERSION) })
            .build()
        // Runs from App.onCreate: a scheduling failure must never stop the app from starting.
        runCatching { js.schedule(job) }
    }

    /**
     * Many people pay for mobile data, so Auto-update's download waits for Wi-Fi or another
     * unmetered network, with the battery not low, in a one-off job that runs the check again.
     */
    private fun waitForWifi(context: Context) {
        val job = JobInfo.Builder(INSTALL_JOB_ID, ComponentName(context, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        runCatching { context.getSystemService(JobScheduler::class.java).schedule(job) }
    }

    /** The download can start now: on an unmetered network with the battery not low. */
    private fun canDownload(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) return false
        // A sticky broadcast: registering for it with no receiver just reads the last one.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) != true
    }

    // --- Check / download / install ---

    /**
     * Checks for a newer release and, if there is one, downloads and installs it, on a background
     * thread. Progress goes to [status]. [manual] checks also retry a version that failed to
     * install before. Returns false, without calling [done], when a check is already running.
     * [done] runs on the main thread unless the check was stopped.
     */
    fun run(context: Context, manual: Boolean, done: () -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return false
        val c = Check(manual)
        check = c
        if (!manual) Prefs.updateDailyAt = System.currentTimeMillis()
        main.post(::changed)
        fun status(res: Int, vararg args: Any) {
            val text = app.getString(res, *args)
            main.post {
                if (c.stopped) return@post
                status = text
                changed()
            }
        }
        thread(name = "chronotif-update") {
            var halted = false
            try {
                status(R.string.update_checking)
                val r = latest(c)
                c.goOn()
                when {
                    r == null || !Version.isNewer(r.version, BuildConfig.VERSION_NAME) -> {
                        outcome(Outcome.CURRENT, manual = manual)
                        status(R.string.update_none, BuildConfig.VERSION_NAME)
                    }
                    !manual && r.version == Prefs.updateFailedVersion -> {
                        outcome(Outcome.SKIPPED, manual = manual)
                        status(R.string.update_skipped, r.version)
                    }
                    !manual && !Prefs.autoUpdate -> {
                        offer(app, r.version)
                        outcome(Outcome.AVAILABLE, manual = manual)
                        status(R.string.update_available_status, r.version)
                    }
                    !manual && !canDownload(app) -> {
                        waitForWifi(app)
                        outcome(Outcome.WAITING, manual = manual)
                        status(R.string.update_waiting, r.version)
                    }
                    else -> {
                        status(R.string.update_downloading, r.version)
                        val apk = download(app, r, c)
                        status(R.string.update_installing, r.version)
                        // Kept before the commit: the installer's answer can arrive before commit returns.
                        outcome(Outcome.INSTALLING, manual = manual)
                        install(app, apk, r.version, c)
                    }
                }
            } catch (e: RateLimited) {
                outcome(Outcome.RATE_LIMITED, manual = manual)
                status(R.string.update_rate_limited)
            } catch (e: Exception) {
                // A stopped check's connection was cut from another thread, so any error after a stop is the stop.
                halted = e is Stopped || c.stopped
                when {
                    // Auto-update turned off mid-check isn't a failure worth keeping.
                    halted && !c.stopped -> Unit
                    halted -> outcome(Outcome.STOPPED, manual = manual)
                    e is HttpFailure -> outcome(Outcome.HTTP, e.code, manual)
                    e is BadChecksum -> outcome(Outcome.CHECKSUM, manual = manual)
                    else -> outcome(Outcome.ERROR, manual = manual)
                }
                if (!halted) status(R.string.update_error, e.message ?: e.javaClass.simpleName)
            } finally {
                check = null
                running.set(false)
                main.post {
                    // A halted check's half-done line ("Downloading…") would otherwise stay on screen.
                    if (halted) status = null
                    changed()
                    // After onStopJob, the job mustn't be finished again.
                    if (!c.stopped) done()
                }
            }
        }
        return true
    }

    /**
     * Stops the daily job's check: Android stopped the job (its network or battery constraint
     * no longer holds) or it was cancelled. Cutting the connection ends a blocked read at once.
     */
    fun stopDaily() {
        val c = check?.takeIf { !it.manual } ?: return
        c.stopped = true
        c.connection?.disconnect()
    }

    /**
     * Stable follows the /releases/latest redirect, which skips prereleases, so frequent nightlies
     * cannot hide it. Nightly reads the releases feed, which lists recent releases without drafts.
     */
    private fun latest(c: Check): Release? {
        val nightly = Prefs.nightlyUpdates
        val tags = if (nightly) {
            feedTags(get("$REPO/releases.atom", c) { it.readBytes().decodeToString() })
        } else {
            listOfNotNull(latestTag(c))
        }
        return selectRelease(tags, nightly)
    }

    /** The tag that /releases/latest redirects to, or null when there's no stable release yet. */
    private fun latestTag(check: Check): String? {
        val c = open("$REPO/releases/latest", check)
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            if (code !in 300..399) throw failure(code)
            return c.getHeaderField("Location")?.substringAfter("/releases/tag/", "")?.ifBlank { null }
        } finally {
            check.connection = null
            c.disconnect()
        }
    }

    /** Release tags in the Atom feed. Release notes are escaped there, so their links can't match. */
    internal fun feedTags(atom: String): List<String> =
        Regex("\"${Regex.escape(REPO)}/releases/tag/([^\"]+)\"").findAll(atom).map { it.groupValues[1] }.toList()

    internal fun selectRelease(tags: List<String>, nightly: Boolean): Release? {
        var best: Release? = null
        for (tag in tags) {
            val version = Version.releaseVersion(tag, nightly) ?: continue
            if (best == null || Version.isNewer(version, best.version)) {
                val apk = "$REPO/releases/download/$tag/chronotif-$tag.apk"
                best = Release(version, apk, "$apk.sha256")
            }
        }
        return best
    }

    private fun download(context: Context, r: Release, c: Check): File {
        val expected = get(r.sumUrl, c) { it.readBytes().decodeToString() }
            .let(Version::sha256Of) ?: throw BadChecksum("bad checksum file")
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        get(r.apkUrl, c) { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    c.goOnInstalling()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_APK_BYTES) throw IOException("download too large")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw BadChecksum("checksum mismatch")
        }
        return file
    }

    private fun install(context: Context, apk: File, version: String, c: Check) {
        val installer = context.packageManager.packageInstaller
        // Drop sessions left over from an update the user never confirmed.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Auto-update may have been turned off during the download.
                c.goOnInstalling()
            val result = Intent(context, UpdateReceiver::class.java)
                .setAction(UpdateReceiver.ACTION_STATUS)
                .putExtra(EXTRA_VERSION, version)
            // Mutable: the installer adds the status and confirmation intent as extras.
            val pi = PendingIntent.getBroadcast(
                context, id, result,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        } finally {
            apk.delete()
        }
    }

    /** A connection [check] can cut; the caller disconnects it and clears [Check.connection]. */
    private fun open(url: String, check: Check): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        check.connection = c
        // Stopped before the connection was there to cut.
        try {
            check.goOn()
        } catch (e: Stopped) {
            check.connection = null
            throw e
        }
        return c
    }

    /** Reads [url]'s body with [read], then closes the stream and the connection. */
    private fun <T> get(url: String, check: Check, read: (InputStream) -> T): T {
        val c = open(url, check)
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw failure(code)
            return c.inputStream.use(read)
        } finally {
            check.connection = null
            c.disconnect()
        }
    }

    private fun failure(code: Int) = if (code == 403 || code == 429) RateLimited() else HttpFailure(code)

    // --- Notifications ---

    /** Update notifications can show: Chronotif's notifications are allowed and its Updates channel isn't off. */
    fun canNotify(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        // Before the first update notification the channel doesn't exist, and nothing has turned it off.
        return nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Update available: a notice for [version], posted once per build, so swiping it away is
     * enough. Its tap opens Settings, which installs it. Settings also offers it while it's newer.
     */
    internal fun offer(context: Context, version: String) {
        Prefs.updateAvailable = version
        // Blocked notifications don't use up the build's one notice: it shows once they're allowed.
        if (Prefs.updateOffered == version || !canNotify(context)) return
        Prefs.updateOffered = version
        val tap = PendingIntent.getActivity(
            context, 2,
            Intent(context, SetupActivity::class.java).setAction(ACTION_INSTALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            context, context.getString(R.string.update_available_title, version),
            context.getString(R.string.update_available_body), tap,
        )
    }

    /** The version Update available offers, while it's newer than this one. */
    val available: String?
        get() = Prefs.updateAvailable?.takeIf { Version.isNewer(it, BuildConfig.VERSION_NAME) }

    fun notify(context: Context, title: String, text: String, tap: PendingIntent?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = tap ?: PendingIntent.getActivity(
            context, 0, Intent(context, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clearNotification(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}

/**
 * The daily background check, on any network, and the one-off run that downloads an update once
 * Wi-Fi is back and the battery isn't low.
 */
class UpdateJob : JobService() {
    // False when a check from Settings is already running: it does this job's work.
    override fun onStartJob(params: JobParameters): Boolean =
        Updater.run(this, manual = false, done = { jobFinished(params, false) })

    // True: a download cut short by leaving Wi-Fi tries again when the constraints hold, not a day later.
    override fun onStopJob(params: JobParameters): Boolean {
        Updater.stopDaily()
        return true
    }
}

/** Install results from PackageInstaller, and the "we were just updated" broadcast. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Prefs.updateFailedVersion = null
            // Only an update counts: a reinstall over the same version also lands here.
            if (Prefs.updateOutcome == Updater.Outcome.INSTALLING.key || Prefs.updateOutcome == Updater.Outcome.CONFIRM.key) {
                Updater.outcome(Updater.Outcome.INSTALLED)
            }
            Updater.sync(context)
            if (Prefs.autoUpdate) {
                Updater.notify(
                    context, context.getString(R.string.update_done_title, BuildConfig.VERSION_NAME),
                    context.getString(R.string.update_done_body), null,
                )
            }
            return
        }
        if (intent.action != ACTION_STATUS) return
        val version = intent.getStringExtra(Updater.EXTRA_VERSION) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Updater.outcome(Updater.Outcome.CONFIRM)
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                if (Updater.foreground) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val pi = PendingIntent.getActivity(
                        context, 1, confirm,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    Updater.notify(
                        context, context.getString(R.string.update_ready_title, version),
                        context.getString(R.string.update_ready_body), pi,
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.outcome(Updater.Outcome.INSTALLED)
                Updater.clearNotification(context)
            }
            // Dismissing the system prompt isn't a failure; the next daily check asks again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.outcome(Updater.Outcome.DISMISSED)
            else -> {
                Updater.outcome(Updater.Outcome.INSTALL_FAILED, status)
                Prefs.updateFailedVersion = version
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Updater.notify(
                    context, context.getString(R.string.update_failed_title, version),
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                        context.getString(R.string.update_failed_signature)
                    } else {
                        why
                    },
                    null,
                )
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "app.chronotif.UPDATE_STATUS"
    }
}
