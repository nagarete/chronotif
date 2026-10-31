// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import app.chronotif.core.AlertClock
import app.chronotif.core.Attention
import app.chronotif.core.Kind
import app.chronotif.core.Meta

class Entry(
    val sbn: StatusBarNotification,
    var ranking: Ranking,
    override val alertTime: Long,
) : Meta {
    val key: String get() = sbn.key
    val n: Notification get() = sbn.notification
    val pkg: String get() = sbn.packageName
    /** One app for one user. */
    val appId = "${sbn.packageName}|${sbn.user.hashCode()}"
    override val kind = Content.kind(sbn)
    /** A conversation that lists its messages gets its own stack; anything else stacks by app. */
    override val stackId =
        if (kind == Kind.NOTIFICATION && n.extras.containsKey(Notification.EXTRA_MESSAGES)) "$appId|${sbn.key}" else appId
    val clearable: Boolean get() = sbn.isClearable
    val isSummary get() = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
    /** Includes the system's auto-grouping, so its empty summaries can be hidden too. */
    val groupId: String? get() = if (sbn.isGroup) sbn.groupKey else null
}

/**
 * In-memory mirror of the active notifications, fed by [ListenerService].
 * Everything runs on the main thread (listener callbacks are delivered there).
 * Nothing here is persisted.
 */
object NotifStore {
    private val entries = LinkedHashMap<String, Entry>()
    /** Message cards swiped away, by notification key. Android can't remove one message, so only we hide it. */
    private val hidden = HashMap<String, MutableSet<String>>()
    private val clock = AlertClock()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var pending = false

    var connected = false
        private set

    fun get(key: String): Entry? = entries[key]

    fun hiddenMessages(key: String): Set<String> = hidden[key].orEmpty()

    fun hideMessage(key: String, messageId: String) {
        if (hidden.getOrPut(key) { HashSet() }.add(messageId)) changed()
    }

    /** What the UI shows: group summaries with children, suspended apps and DND-hidden ones drop out. */
    fun visible(): List<Entry> {
        val groupsWithChildren = HashSet<String>()
        for (e in entries.values) if (!e.isSummary) e.groupId?.let(groupsWithChildren::add)
        return entries.values.filter { e ->
            e.pkg != BuildConfig.APPLICATION_ID &&
                !e.ranking.isSuspended &&
                e.ranking.suppressedVisualEffects and
                android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST == 0 &&
                !(e.isSummary && e.groupId in groupsWithChildren)
        }
    }

    /** A conversation whose every message was swiped away isn't New: there's nothing left to show. */
    fun newCount(): Int {
        val lastCheck = Prefs.lastCheck
        return visible().count { Attention.isNew(it, lastCheck) && !allHidden(it) }
    }

    private fun allHidden(e: Entry): Boolean {
        val h = hidden[e.key] ?: return false
        val ids = Content.incomingIds(e.n)
        return ids.isNotEmpty() && h.containsAll(ids)
    }

    fun connect(active: Array<StatusBarNotification>?, map: RankingMap) {
        connected = true
        entries.clear()
        hidden.keys.retainAll(active.orEmpty().map { it.key }.toSet())
        clock.clear()
        active?.sortedBy { it.postTime }?.forEach { put(it, map) }
        changed()
    }

    fun disconnect() {
        connected = false
        entries.clear()
        hidden.clear()
        clock.clear()
        changed()
    }

    fun post(sbn: StatusBarNotification, map: RankingMap) {
        put(sbn, map)
        changed()
    }

    fun remove(key: String, map: RankingMap?) {
        entries.remove(key)
        hidden.remove(key)
        clock.onRemoved(key)
        map?.let(::rerank)
        changed()
    }

    fun rank(map: RankingMap) {
        rerank(map)
        changed()
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Coalesces bursts (apps often post several notifications at once) into one UI update. */
    fun changed() {
        if (pending) return
        pending = true
        main.post {
            pending = false
            listeners.toList().forEach { it() }
        }
    }

    private fun put(sbn: StatusBarNotification, map: RankingMap) {
        val n = sbn.notification
        val live = Content.kind(sbn) != Kind.NOTIFICATION
        val quiet = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 || live
        val alertTime = clock.onPosted(sbn.key, sbn.postTime, Content.signature(n), quiet,
            conversation = !live && Content.isConversation(n),
            message = Content.incomingMessage(n))
        entries.remove(sbn.key)
        entries[sbn.key] = Entry(sbn, rankingOf(sbn.key, map), alertTime)
    }

    private fun rerank(map: RankingMap) {
        for (e in entries.values) e.ranking = rankingOf(e.key, map)
    }

    private fun rankingOf(key: String, map: RankingMap) = Ranking().also { map.getRanking(key, it) }
}
