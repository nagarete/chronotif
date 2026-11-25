// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.content.Context
import app.chronotif.core.Usage

/**
 * Where the Panel and the lock-screen view get the notifications they draw, the last check that
 * splits them into New and Earlier, and where what the user does with a card goes. [Real] is the
 * phone's own notifications, with every action reaching Android or the app. Another source, such
 * as the Tutorial's samples, can stand in without any of it touching Android or the real check.
 */
interface CardSource {
    /** Whether notification access is connected; when it isn't, the list says so. */
    val connected: Boolean

    /** What may be shown, as [NotifStore.visible] gives it. */
    fun visible(): List<Entry>

    fun get(key: String): Entry?

    /** Messages of [key]'s conversation hidden by swiping their card away. */
    fun hiddenMessages(key: String): Set<String>

    /** The time up to which this source's notifications have been checked. */
    val lastCheck: Long

    /** The Panel was seen up to [seenUpTo]: what arrived by then moves to Earlier. */
    fun markChecked(seenUpTo: Long)

    fun addListener(l: () -> Unit)

    fun removeListener(l: () -> Unit)

    /** Opens what the notification points to. True if there was something to open. */
    fun open(context: Context, e: Entry): Boolean

    /** Runs one of [e]'s buttons. True if it's expected to bring up an activity. */
    fun action(context: Context, e: Entry, a: Notification.Action): Boolean

    fun reply(context: Context, e: Entry, a: Notification.Action, text: CharSequence): Boolean

    fun dismissAll(entries: List<Entry>)

    /** Hides one message of a conversation: Android can't drop it from the notification. */
    fun hideMessage(key: String, messageId: String)

    fun snooze(e: Entry, until: Long)

    /** Swipes a paused Media player away; false when it can't go (see [MediaDismiss]). */
    fun dismissMedia(context: Context, e: Entry): Boolean

    /** What the user did in the Panel that a stand-in source may follow, as the Tutorial does. */
    enum class Noticed { PANEL_OPENED, PANEL_CLOSED, STACK_OPENED, STACK_CLOSED, PREVIEW, SEARCH }

    fun noticed(what: Noticed) {}

    /** The phone's notifications, as Android delivers them, and the real last check. */
    object Real : CardSource {
        override val connected get() = NotifStore.connected
        override fun visible() = NotifStore.visible()
        override fun get(key: String) = NotifStore.get(key)
        override fun hiddenMessages(key: String) = NotifStore.hiddenMessages(key)
        override val lastCheck get() = Prefs.lastCheck
        override fun markChecked(seenUpTo: Long) = Prefs.markChecked(seenUpTo)
        override fun addListener(l: () -> Unit) = NotifStore.addListener(l)
        override fun removeListener(l: () -> Unit) = NotifStore.removeListener(l)
        override fun open(context: Context, e: Entry) = Act.open(context, e).also { if (it) count(Usage.Count.NOTIFICATION_OPENED) }
        // Act can't tell a sample by its button alone, so the backstop for buttons and replies is here.
        override fun action(context: Context, e: Entry, a: Notification.Action): Boolean {
            if (e.isSample) return false
            val sent = Act.action(context, a)
            if (sent != Act.Sent.FAILED) count(Usage.Count.ACTION_TAPPED)
            return sent == Act.Sent.OPENS
        }
        override fun reply(context: Context, e: Entry, a: Notification.Action, text: CharSequence) =
            (!e.isSample && Act.reply(context, a, text)).also { if (it) count(Usage.Count.REPLY_SENT) }
        override fun dismissAll(entries: List<Entry>) {
            Act.dismissAll(entries)
            count(Usage.Count.CLEARED)
        }
        override fun hideMessage(key: String, messageId: String) = NotifStore.hideMessage(key, messageId)
        override fun snooze(e: Entry, until: Long) {
            Act.snooze(e, until)
            count(Usage.Count.SNOOZED)
        }
        override fun dismissMedia(context: Context, e: Entry) =
            MediaDismiss.dismiss(context, e).also { if (it) count(Usage.Count.MEDIA_DISMISSED) }

        // Only the real Panel is counted for Usage data: the Tutorial has its own source.
        override fun noticed(what: Noticed) = when (what) {
            Noticed.PANEL_OPENED -> count(Usage.Count.PANEL_OPENED)
            Noticed.PANEL_CLOSED -> UsageData.sendIfDue()
            Noticed.STACK_OPENED -> count(Usage.Count.STACK_OPENED)
            Noticed.PREVIEW -> count(Usage.Count.PREVIEW_OPENED)
            Noticed.SEARCH -> count(Usage.Count.SEARCH_OPENED)
            Noticed.STACK_CLOSED -> Unit
        }

        private fun count(c: Usage.Count) = UsageData.count(c)
    }
}
