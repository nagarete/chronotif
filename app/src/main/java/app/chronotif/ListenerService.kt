// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.chronotif.ui.LockActivity

/**
 * Bound by the system while notification access is granted; purely event-driven (no polling, timers, wakelocks or foreground service).
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
    }

    private var handle: EdgeHandle? = null
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (LockPolicy.shouldShow(context)) showLockView()
                Intent.ACTION_SCREEN_OFF -> {
                    LockSession.reset()
                    UsageData.flush()
                }
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
        // The first panel after an update or restart would otherwise read every app's icon and name.
        Apps.warm(this, NotifStore.visible())
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

    /** Reloads every active notification, e.g. after demo mode stops dropping them. */
    fun resync() {
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            null
        }
        NotifStore.connect(active, currentRanking)
    }

    override fun onListenerDisconnected() {
        teardown()
        NotifStore.disconnect()
        // Ask to be rebound; some ROMs drop listeners after the process is killed.
        requestRebind(ComponentName(this, ListenerService::class.java))
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
        Apps.warm(this, NotifStore.visible())
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
        } catch (e: RuntimeException) {
            Log.w("ListenerService", "Couldn't show the lock-screen view", e)
        }
    }
}
