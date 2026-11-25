// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * The attention model, free of Android types so it can be unit-tested.
 *
 * Every notification carries an [Meta.alertTime]: when it last *arrived* (first post, or an
 * update that genuinely brought new content). A single watermark, the time of the user's last
 * meaningful check, splits notifications into NEW (arrived after it) and EARLIER (already seen).
 * Both are in time order. In EARLIER, one app's cards that sit next to each other and arrived close
 * together fold into a stack ([appStacks]). A conversation's messages are split the same way as
 * notifications ([split]).
 */
interface Meta {
    val alertTime: Long
    val kind: Kind
}

/** Only [NOTIFICATION]s take part in NEW/EARLIER; the rest are live state, never "new". */
enum class Kind {
    NOTIFICATION,
    /** Ongoing or incoming call: shown first. */
    CALL,
    /** A playing or paused media player: shown after calls. */
    MEDIA,
    /** Background work (services, connected devices, downloads, navigation): collapsed last. */
    RUNNING,
}

class Sections<T>(
    val calls: List<T>,
    val media: List<T>,
    val fresh: List<T>,
    val earlier: List<T>,
    val running: List<T>,
) {
    /** Running work alone doesn't make the list non-empty in any way worth announcing. */
    val isEmpty get() = calls.isEmpty() && media.isEmpty() && fresh.isEmpty() && earlier.isEmpty()
    val earlierCount get() = earlier.size
}

object Attention {

    /** Newest first everywhere, EARLIER included; [appStacks] folds EARLIER only where that keeps time order. */
    fun <T : Meta> sections(items: Collection<T>, lastCheck: Long): Sections<T> {
        val byRecent = items.sortedByDescending { it.alertTime }
        val calls = ArrayList<T>()
        val media = ArrayList<T>()
        val running = ArrayList<T>()
        val fresh = ArrayList<T>()
        val earlier = ArrayList<T>()
        for (item in byRecent) when (item.kind) {
            Kind.CALL -> calls += item
            Kind.MEDIA -> media += item
            Kind.RUNNING -> running += item
            Kind.NOTIFICATION ->
                if (item.alertTime > lastCheck) fresh += item
                else earlier += item
        }
        return Sections(calls, media, fresh, earlier, running)
    }

    /** How far behind an app stack's newest card another card from that app may be and still join it. */
    const val STACK_WINDOW = 30 * 60_000L

    /**
     * Folds EARLIER's [cards] (newest first) into app stacks (ADR 0004). A card joins the stack above it
     * only when it comes straight after it, from the same [app], within [window] of the stack's newest
     * card, so a stack never holds an old card above a newer one from another app. A null [app] stands
     * alone (a conversation that is already its own stack).
     */
    fun <T> appStacks(
        cards: List<T>, time: (T) -> Long, app: (T) -> String?, window: Long = STACK_WINDOW,
    ): List<List<T>> {
        val stacks = ArrayList<MutableList<T>>()
        for (card in cards) {
            val stack = stacks.lastOrNull()
            val a = app(card)
            if (stack != null && a != null && app(stack[0]) == a && time(stack[0]) - time(card) <= window) {
                stack += card
            } else stacks += mutableListOf(card)
        }
        return stacks
    }

    fun isNew(item: Meta, lastCheck: Long) = item.kind == Kind.NOTIFICATION && item.alertTime > lastCheck

    /**
     * How many of a New conversation's incoming messages (send times, oldest first) are New: those
     * sent after the check, and at least the latest, since its arrival is what made it New.
     */
    fun newMessages(times: List<Long>, lastCheck: Long): Int =
        times.count { it > lastCheck }.coerceIn(minOf(1, times.size), times.size)

    /**
     * A conversation's incoming [messages] (oldest first) split at the check (ADR 0002), with the
     * [hidden] ones left out of both parts. Only a [New][isNew] conversation has New messages, and
     * which ones they are ([newMessages]) is decided before hiding: swiping away the only New
     * message leaves nothing New rather than moving a checked message up into New.
     */
    fun <M> split(
        item: Meta, lastCheck: Long, messages: List<M>, time: (M) -> Long, hidden: (M) -> Boolean,
    ): Split<M> {
        val newCount = if (isNew(item, lastCheck)) newMessages(messages.map(time), lastCheck) else 0
        val cut = messages.size - newCount
        return Split(messages.subList(cut, messages.size).filterNot(hidden), messages.subList(0, cut).filterNot(hidden))
    }

    /**
     * Whether [item] shows anything in New: a New notification without incoming [messages], or a
     * New conversation with one of its New messages still shown ([split]). The list, the edge
     * handle, the tile and the lock screen's wake all ask this, so they agree on what is New.
     */
    fun <M> showsNew(item: Meta, lastCheck: Long, messages: List<M>, time: (M) -> Long, hidden: (M) -> Boolean): Boolean =
        isNew(item, lastCheck) && (messages.isEmpty() || split(item, lastCheck, messages, time, hidden).new.isNotEmpty())

    /** A check never moves the watermark backwards. */
    fun advance(lastCheck: Long, seenUpTo: Long) = maxOf(lastCheck, seenUpTo)
}

/** A conversation's shown messages in New and in Earlier, both oldest first: see [Attention.split]. */
class Split<M>(val new: List<M>, val earlier: List<M>)

/** Identity of the latest incoming message; zero time means the app supplied only plain text. */
data class MessageStamp(val time: Long, val signature: Int)

/**
 * Tracks when each notification last arrived. Apps re-post the same key constantly
 * (progress, timestamps, silent refreshes). Incoming conversation messages count even when
 * silent; other content changes count only when the app didn't mark the update as quiet.
 * A notification coming back from a snooze ([returned]) arrives when it's posted (ADR 0010).
 */
class AlertClock {
    private class State(val alertTime: Long, val signature: Int, val message: MessageStamp?)

    private val states = HashMap<String, State>()

    fun onPosted(
        key: String, postTime: Long, signature: Int, quiet: Boolean,
        conversation: Boolean = false, message: MessageStamp? = null, returned: Boolean = false,
    ): Long {
        val prev = states[key]
        val incoming = message != null && (prev?.message == null ||
            message.time > prev.message.time ||
            (message.time == 0L && message != prev.message))
        val arrived = if (conversation) incoming else !quiet && signature != prev?.signature
        val alertTime = when {
            returned -> postTime
            // First sight of a chat (after a restart, or an app re-alerting old chats): it arrived with
            // its latest message, not now. The sender's clock may run a little ahead, hence min.
            prev == null && conversation && message != null && message.time > 0 -> minOf(postTime, message.time)
            prev == null || arrived -> postTime
            else -> prev.alertTime
        }
        // Keep the newest incoming marker when an app trims history or adds an outgoing reply.
        val latest = if (message != null && (prev?.message == null || message.time >= prev.message.time)) {
            message
        } else prev?.message
        states[key] = State(alertTime, signature, latest)
        return alertTime
    }

    fun onRemoved(key: String) {
        states.remove(key)
    }

    fun clear() = states.clear()
}

/** Short relative timestamps, iOS style: now, 5m, 3h, Yesterday, then a date label. */
object RelTime {
    sealed interface Label {
        data object Now : Label
        data class Minutes(val m: Int) : Label
        data class Hours(val h: Int) : Label
        data object Yesterday : Label
        data object Older : Label
    }

    fun of(now: Long, t: Long, startOfToday: Long): Label {
        val d = (now - t).coerceAtLeast(0)
        return when {
            d < 60_000 -> Label.Now
            d < 3_600_000 -> Label.Minutes((d / 60_000).toInt())
            t >= startOfToday || d < 6 * 3_600_000 -> Label.Hours((d / 3_600_000).toInt())
            t >= startOfToday - 86_400_000 -> Label.Yesterday
            else -> Label.Older
        }
    }
}
