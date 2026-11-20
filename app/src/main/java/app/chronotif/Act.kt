// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Performs notification interactions on behalf of the user. */
object Act {

    /** Opens the notification. Returns true if there was something to open. */
    fun open(context: Context, e: Entry): Boolean {
        val pi = e.n.contentIntent ?: return false
        val sent = send(context, pi)
        if (sent && e.n.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(e)
        return sent
    }

    fun dismiss(e: Entry) {
        if (e.clearable) ListenerService.instance?.cancelNotification(e.key)
    }

    fun dismissAll(entries: List<Entry>) {
        val keys = entries.filter { it.clearable }.map { it.key }
        if (keys.isNotEmpty()) ListenerService.instance?.cancelNotifications(keys.toTypedArray())
    }

    /** Hides [e] until [until], when Android posts it again and it counts as New (ADR 0010). */
    fun snooze(e: Entry, until: Long) {
        val service = ListenerService.instance ?: return
        if (!e.clearable) return
        Prefs.snoozed(e.key, until)
        service.snoozeNotification(e.key, (until - System.currentTimeMillis()).coerceAtLeast(60_000L))
    }

    /** Returns true if the action is expected to bring up an activity (the panel should close). */
    fun action(context: Context, a: Notification.Action): Boolean {
        val pi = a.actionIntent ?: return false
        return send(context, pi) && launchesActivity(pi)
    }

    fun reply(context: Context, a: Notification.Action, text: CharSequence): Boolean {
        val inputs = a.remoteInputs ?: return false
        val results = Bundle()
        for (input in inputs) if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return send(context, a.actionIntent, fill)
    }

    /**
     * Whether [a] has to wait for an unlock on the lock screen: when the app asks for that, or
     * when it brings up an activity, which Android can't show over the keyguard. A reply or a
     * button that works in the background runs right away (ADR 0013).
     */
    fun needsUnlock(a: Notification.Action): Boolean {
        if (Build.VERSION.SDK_INT < 31) return replyInput(a) == null
        if (a.isAuthenticationRequired) return true
        val pi = a.actionIntent ?: return false
        return replyInput(a) == null && pi.isActivity
    }

    fun replyInput(a: Notification.Action): RemoteInput? =
        a.remoteInputs?.firstOrNull { it.allowFreeFormInput }

    private fun launchesActivity(pi: PendingIntent) =
        if (Build.VERSION.SDK_INT >= 31) pi.isActivity else true

    /** We send while visible to the user, so lend the target our right to start activities. */
    @Suppress("DEPRECATION")
    private fun send(context: Context, pi: PendingIntent, fill: Intent? = null): Boolean {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        return try {
            pi.send(context, 0, fill, null, null, null, options.toBundle())
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }
}
