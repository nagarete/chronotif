// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking
import app.chronotif.core.Attention
import app.chronotif.core.Kind

/** Decides when the lock-screen view appears and what it may reveal. */
object LockPolicy {
    enum class Vis { SHOW, REDACT, HIDE }

    /**
     * Mirrors SystemUI: content is only hidden when the device has a secure lock. The most
     * restrictive of the app's notification visibility and the channel override wins; the
     * system "hide sensitive content" setting (or ours) redacts everything not marked public.
     */
    fun visibility(context: Context, e: Entry): Vis {
        val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        if (!secure) return if (Prefs.lockHideContent) Vis.REDACT else Vis.SHOW

        var v = e.n.visibility
        val overrides = listOf(
            if (Build.VERSION.SDK_INT >= 31) e.ranking.lockscreenVisibilityOverride else null,
            e.ranking.channel?.lockscreenVisibility,
        )
        for (o in overrides) if (o != null && o != Ranking.VISIBILITY_NO_OVERRIDE) v = minOf(v, o)
        if (v == Notification.VISIBILITY_SECRET) return Vis.HIDE
        if (v == Notification.VISIBILITY_PUBLIC) return Vis.SHOW
        return if (Prefs.lockHideContent || !systemAllowsPrivate(context)) Vis.REDACT else Vis.SHOW
    }

    fun shouldShow(context: Context): Boolean {
        val mode = Prefs.lockMode
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Chronotif", "lock? enabled=${Prefs.enabled} mode=$mode connected=${NotifStore.connected} locked=$locked " +
                "system=${systemShowsNotifications(context)} busy=${busy(context)} new=${NotifStore.newCount()}")
        }
        if (!Prefs.enabled || mode == Prefs.LOCK_OFF || !NotifStore.connected) return false
        if (!locked) return false
        if (!systemShowsNotifications(context)) return false
        if (busy(context)) return false

        val lastCheck = Prefs.lastCheck
        // Calls, media and running work never bring the view up on their own.
        val shown = NotifStore.visible().filter { it.kind == Kind.NOTIFICATION && visibility(context, it) != Vis.HIDE }
        return when (mode) {
            Prefs.LOCK_ALWAYS -> shown.isNotEmpty()
            else -> shown.any { Attention.isNew(it, lastCheck) }
        }
    }

    /** Never cover a ringing call or an alarm/call screen that just launched full-screen. */
    private fun busy(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java).mode
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        ) return true
        val now = System.currentTimeMillis()
        // An Excluded app's alarm or call still rings on screen; Chronotif mustn't cover it.
        return NotifStore.visible(includeExcluded = true).any {
            it.n.fullScreenIntent != null && now - it.sbn.postTime < 60_000 &&
                (it.n.category == Notification.CATEGORY_CALL || it.n.category == Notification.CATEGORY_ALARM)
        }
    }

    // Hidden-but-readable secure settings. If a ROM blocks reading them, fail private.
    private fun systemShowsNotifications(context: Context) =
        secureInt(context, "lock_screen_show_notifications", default = 1, unreadable = 1) != 0

    private fun systemAllowsPrivate(context: Context) =
        secureInt(context, "lock_screen_allow_private_notifications", default = 1, unreadable = 0) != 0

    private fun secureInt(context: Context, name: String, default: Int, unreadable: Int) = try {
        Settings.Secure.getInt(context.contentResolver, name, default)
    } catch (_: Exception) {
        unreadable
    }
}

/**
 * Lock-screen viewing counts as a check only once the user actually unlocks after seeing
 * it (like iOS moving lock-screen notifications into Notification Center on unlock).
 */
object LockSession {
    private var seenUpTo = 0L

    fun seen(upTo: Long) {
        if (BuildConfig.DEBUG) android.util.Log.d("Chronotif", "lock seen upTo=$upTo")
        seenUpTo = maxOf(seenUpTo, upTo)
    }

    fun unlocked() {
        if (BuildConfig.DEBUG) android.util.Log.d("Chronotif", "unlocked seenUpTo=$seenUpTo")
        if (seenUpTo > 0) Prefs.markChecked(seenUpTo)
        seenUpTo = 0
    }

    fun reset() {
        if (BuildConfig.DEBUG) android.util.Log.d("Chronotif", "lock session reset")
        seenUpTo = 0
    }
}
