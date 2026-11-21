// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.content.Context

/**
 * The Tutorial's notifications: [Samples] with a last check of their own. What the user does with
 * them changes only these samples and is reported as an [Event], so the Tutorial can tell when a
 * step is done. Nothing reaches Android, another app or the real last check.
 */
class TutorialSource(initial: List<Entry>, lastCheck: Long, internal var onEvent: (Event) -> Unit) : CardSource {

    enum class Event {
        PANEL_OPENED, PANEL_CLOSED, STACK_OPENED, STACK_CLOSED, PREVIEW, CHECKED, REPLIED, SNOOZED, CLEARED,
        /** Search pulled down in the Panel, opened anywhere else, an answer shown, an app tapped. */
        SEARCH, SEARCH_ELSEWHERE, ANSWER_SHOWN, APP_TAPPED,
    }

    private val entries = initial.toMutableList()
    private val hidden = HashMap<String, MutableSet<String>>()
    private val listeners = ArrayList<() -> Unit>()

    override var lastCheck = lastCheck
        private set

    override val connected get() = true

    /** Like [NotifStore.visible]: a conversation with every message hidden is gone. */
    override fun visible(): List<Entry> = entries.filter { e ->
        val ids = Content.incomingIds(e.n)
        ids.isEmpty() || !hiddenMessages(e.key).containsAll(ids)
    }

    override fun get(key: String) = entries.firstOrNull { it.key == key }

    override fun hiddenMessages(key: String): Set<String> = hidden[key].orEmpty()

    override fun markChecked(seenUpTo: Long) {
        if (seenUpTo <= lastCheck) return
        lastCheck = seenUpTo
        changed()
        onEvent(Event.CHECKED)
    }

    override fun addListener(l: () -> Unit) {
        listeners += l
    }

    override fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Adds [e], or replaces the sample with its key: a new message in a conversation. */
    fun post(e: Entry) {
        entries.removeAll { it.key == e.key }
        entries += e
        changed()
    }

    // A sample has nothing to open: its card opens in place instead.
    override fun open(context: Context, e: Entry) = false

    override fun action(context: Context, e: Entry, a: Notification.Action) = false

    override fun reply(context: Context, e: Entry, a: Notification.Action, text: CharSequence): Boolean {
        onEvent(Event.REPLIED)
        return true
    }

    override fun dismissAll(entries: List<Entry>) {
        val gone = entries.filter { it.clearable }.map { it.key }.toSet()
        if (gone.isEmpty()) return
        this.entries.removeAll { it.key in gone }
        changed()
        onEvent(Event.CLEARED)
    }

    override fun hideMessage(key: String, messageId: String) {
        hidden.getOrPut(key) { HashSet() } += messageId
        changed()
        onEvent(Event.CLEARED)
    }

    override fun snooze(e: Entry, until: Long) {
        entries.removeAll { it.key == e.key }
        changed()
        onEvent(Event.SNOOZED)
    }

    override fun dismissMedia(context: Context, e: Entry) = false

    override fun noticed(what: CardSource.Noticed) = onEvent(Event.valueOf(what.name))

    private fun changed() = listeners.toList().forEach { it() }
}
