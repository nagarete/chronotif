// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.chronotif.ui.LockActivity
import java.util.concurrent.TimeUnit

/**
 * The only long-lived component. Bound by the system while notification access is granted;
 * event-driven (no polling, wakelocks or foreground service). [ListenerWatchdog] only checks
 * that the system hasn't silently dropped it.
 */
class ListenerService : NotificationListenerService() {

    companion object {
        var instance: ListenerService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val me = ComponentName(context, ListenerService::class.java)
            return flat?.split(':')?.any { ComponentName.unflattenFromString(it) == me } == true
        }

        /** Wait before judging: a process just started to bind the listener hasn't connected yet. */
        private const val SETTLE_MS = 5_000L
        private const val NUDGE_GAP_MS = 60_000L
        private val main = Handler(Looper.getMainLooper())
        private var lastNudge = -NUDGE_GAP_MS

        /**
         * Gets the system to bind the listener again if access is granted but it isn't connected.
         * That happens when the process is killed and the system gives up rebinding (it retries
         * once; some ROMs kill it routinely). Otherwise the only fix is toggling access by hand.
         *
         * [requestRebind] can't do this: it only undoes [requestUnbind]. Any component change in
         * our package makes the system rebind approved listeners that aren't bound, so this flips
         * a dummy component. (Disabling the listener itself would revoke access.) A bound listener
         * is left alone, so a false alarm costs one package-changed broadcast.
         */
        fun ensureBound(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            main.postDelayed({
                try {
                    if (instance == null && isEnabled(app)) nudge(app)
                } finally {
                    done()
                }
            }, SETTLE_MS)
        }

        private fun nudge(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNudge < NUDGE_GAP_MS) return
            lastNudge = now
            runCatching { requestRebind(ComponentName(context, ListenerService::class.java)) }
            val pm = context.packageManager
            val dummy = ComponentName(context, "app.chronotif.RebindNudge")
            val flipped = if (pm.getComponentEnabledSetting(dummy) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            runCatching { pm.setComponentEnabledSetting(dummy, flipped, PackageManager.DONT_KILL_APP) }
        }
    }

    private var handle: EdgeHandle? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (LockPolicy.shouldShow(context)) showLockView()
                Intent.ACTION_SCREEN_OFF -> LockSession.reset()
                Intent.ACTION_USER_PRESENT -> LockSession.unlocked()
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            null
        }
        NotifStore.connect(active, currentRanking)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // Exported so USER_PRESENT is delivered; all three are protected system
            // broadcasts, so no other app can send them to us.
            registerReceiver(screenReceiver, filter, if (Build.VERSION.SDK_INT >= 33) RECEIVER_EXPORTED else 0)
            receiverRegistered = true
        }
        refreshHandle()
    }

    override fun onListenerDisconnected() {
        teardown()
        NotifStore.disconnect()
        // Still granted means the system dropped us rather than the user revoking access.
        ensureBound(this)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        handle?.hide()
        handle = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        NotifStore.post(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        NotifStore.remove(sbn.key, rankingMap)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        NotifStore.rank(rankingMap)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handle?.update()
    }

    /** Applies the current edge-handle settings. Called again from setup when they change. */
    fun refreshHandle() {
        val want = Prefs.enabled && Prefs.handleEnabled && Settings.canDrawOverlays(this)
        if (want) {
            (handle ?: EdgeHandle(this).also { handle = it }).show()
        } else {
            handle?.hide()
            handle = null
        }
    }

    private fun showLockView() {
        // Allowed from the background because the user granted "display over other apps".
        try {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        } catch (_: RuntimeException) {
        }
    }
}

/**
 * Catches a listener the system dropped while our process was dead, when nothing else would
 * start the process to notice. The check is a field read and usually a no-op.
 */
class ListenerWatchdog : JobService() {
    companion object {
        private const val JOB_ID = 2

        /** Cheap; called on every app start. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ListenerWatchdog::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(30))
                .setPersisted(true)
                .build()
            // Runs from App.onCreate: a scheduling failure must never stop the app from starting.
            runCatching { js.schedule(job) }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        ListenerService.ensureBound(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
