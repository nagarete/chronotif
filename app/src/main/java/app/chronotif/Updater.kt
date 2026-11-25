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
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.chronotif.core.Version
import app.chronotif.ui.SetupActivity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Opt-in self-update from GitHub Releases: it finds the newest release tag on github.com and
 * downloads the APK plus its `.sha256`.
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
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 1
    const val EXTRA_VERSION = "version"

    class Release(val version: String, val apkUrl: String, val sumUrl: String)

    /** GitHub refused the request, most often because this IP address made too many. */
    class RateLimited : IOException("rate limited")

    /** True while the setup screen is visible, so install prompts open directly instead of notifying. */
    @Volatile var foreground = false

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    // --- Scheduling ---

    /** Keeps the daily check scheduled iff auto-update is on. Cheap; called on every app start. */
    fun sync(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!Prefs.autoUpdate) return js.cancel(JOB_ID)
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        // Runs from App.onCreate: a scheduling failure must never stop the app from starting.
        runCatching { js.schedule(job) }
    }

    // --- Check / download / install ---

    /**
     * Checks for a newer release and, if there is one, downloads and installs it.
     * Runs on a background thread; [onStatus] (main thread) gets human-readable progress.
     * [manual] checks also retry a version that failed to install before.
     */
    fun run(context: Context, manual: Boolean, onStatus: (String) -> Unit = {}, done: () -> Unit = {}) {
        val app = context.applicationContext
        fun status(res: Int, vararg args: Any) = main.post { onStatus(app.getString(res, *args)) }
        if (!running.compareAndSet(false, true)) {
            status(R.string.update_busy)
            return done()
        }
        thread(name = "chronotif-update") {
            try {
                status(R.string.update_checking)
                val r = latest()
                when {
                    r == null || !Version.isNewer(r.version, BuildConfig.VERSION_NAME) ->
                        status(R.string.update_none, BuildConfig.VERSION_NAME)
                    !manual && r.version == Prefs.updateFailedVersion -> status(R.string.update_skipped, r.version)
                    else -> {
                        status(R.string.update_downloading, r.version)
                        val apk = download(app, r)
                        status(R.string.update_installing, r.version)
                        install(app, apk, r.version)
                    }
                }
            } catch (e: RateLimited) {
                status(R.string.update_rate_limited)
            } catch (e: Exception) {
                status(R.string.update_error, e.message ?: e.javaClass.simpleName)
            } finally {
                running.set(false)
                main.post(done)
            }
        }
    }

    /**
     * Stable follows the /releases/latest redirect, which skips prereleases, so frequent nightlies
     * cannot hide it. Nightly reads the releases feed, which lists recent releases without drafts.
     */
    private fun latest(): Release? {
        val nightly = Prefs.nightlyUpdates
        val tags = if (nightly) {
            feedTags(get("$REPO/releases.atom").use { it.readBytes().decodeToString() })
        } else {
            listOfNotNull(latestTag())
        }
        return selectRelease(tags, nightly)
    }

    /** The tag that /releases/latest redirects to, or null when there's no stable release yet. */
    private fun latestTag(): String? {
        val c = open("$REPO/releases/latest")
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            if (code !in 300..399) throw failure(code)
            return c.getHeaderField("Location")?.substringAfter("/releases/tag/", "")?.ifBlank { null }
        } finally {
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

    private fun download(context: Context, r: Release): File {
        val expected = get(r.sumUrl).use { it.readBytes().decodeToString() }
            .let(Version::sha256Of) ?: throw IOException("bad checksum file")
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        get(r.apkUrl).use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
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
            throw IOException("checksum mismatch")
        }
        return file
    }

    private fun install(context: Context, apk: File, version: String) {
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
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
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
        apk.delete()
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Chronotif/${BuildConfig.VERSION_NAME}")
        return c
    }

    private fun get(url: String): java.io.InputStream {
        val c = open(url)
        if (c.responseCode != HttpURLConnection.HTTP_OK) {
            val code = c.responseCode
            c.disconnect()
            throw failure(code)
        }
        return c.inputStream
    }

    private fun failure(code: Int) = if (code == 403 || code == 429) RateLimited() else IOException("HTTP $code")

    // --- Notifications ---

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

/** The daily background check. Only scheduled while auto-update is on. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.run(this, manual = false, done = { jobFinished(params, false) })
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}

/** Install results from PackageInstaller, and the "we were just updated" broadcast. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Prefs.updateFailedVersion = null
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
            PackageInstaller.STATUS_SUCCESS -> Updater.clearNotification(context)
            // Dismissing the system prompt isn't a failure; the next daily check asks again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
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
