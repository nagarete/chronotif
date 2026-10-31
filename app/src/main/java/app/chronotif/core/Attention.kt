// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * The attention model, free of Android types so it can be unit-tested.
 *
 * Every notification carries an [Meta.alertTime]: when it last *arrived* (first post, or an
 * update that genuinely brought new content). A single watermark, the time of the user's last
 * meaningful check, splits notifications into NEW (arrived after it) and EARLIER (already seen).
 * EARLIER is folded into stacks so it recedes instead of cluttering. A conversation's messages
 * are split the same way when it's drawn ([newMessages]).
 */
interface Meta {
    /** Notifications sharing a stack id fold together in EARLIER (one app, one user). */
    val stackId: String
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

class Stack<T>(val id: String, val items: List<T>)

class Sections<T>(
    val calls: List<T>,
    val media: List<T>,
    val fresh: List<T>,
    val earlier: List<Stack<T>>,
    val running: List<T>,
) {
    /** Running work alone doesn't make the list non-empty in any way worth announcing. */
    val isEmpty get() = calls.isEmpty() && media.isEmpty() && fresh.isEmpty() && earlier.isEmpty()
    val earlierCount get() = earlier.sumOf { it.items.size }
}

object Attention {

    /** Newest first everywhere; EARLIER stacks are ordered by their newest member. */
    fun <T : Meta> sections(items: Collection<T>, lastCheck: Long): Sections<T> {
        val byRecent = items.sortedByDescending { it.alertTime }
        val calls = ArrayList<T>()
        val media = ArrayList<T>()
        val running = ArrayList<T>()
        val fresh = ArrayList<T>()
        val earlier = LinkedHashMap<String, MutableList<T>>()
        for (item in byRecent) when (item.kind) {
            Kind.CALL -> calls += item
            Kind.MEDIA -> media += item
            Kind.RUNNING -> running += item
            Kind.NOTIFICATION ->
                if (item.alertTime > lastCheck) fresh += item
                else earlier.getOrPut(item.stackId) { ArrayList() } += item
        }
        return Sections(calls, media, fresh, earlier.map { (id, list) -> Stack(id, list) }, running)
    }

    fun isNew(item: Meta, lastCheck: Long) = item.kind == Kind.NOTIFICATION && item.alertTime > lastCheck

    /**
     * How many of a New conversation's incoming messages (send times, oldest first) are New: those
     * sent after the check, and at least the latest, since its arrival is what made it New.
     */
    fun newMessages(times: List<Long>, lastCheck: Long): Int =
        times.count { it > lastCheck }.coerceIn(minOf(1, times.size), times.size)

    /** A check never moves the watermark backwards. */
    fun advance(lastCheck: Long, seenUpTo: Long) = maxOf(lastCheck, seenUpTo)
}

/** Identity of the latest incoming message; zero time means the app supplied only plain text. */
data class MessageStamp(val time: Long, val signature: Int)

/**
 * Tracks when each notification last arrived. Apps re-post the same key constantly
 * (progress, timestamps, silent refreshes). Incoming conversation messages count even when
 * silent; other content changes count only when the app didn't mark the update as quiet.
 */
class AlertClock {
    private class State(val alertTime: Long, val signature: Int, val message: MessageStamp?)

    private val states = HashMap<String, State>()

    fun onPosted(
        key: String, postTime: Long, signature: Int, quiet: Boolean,
        conversation: Boolean = false, message: MessageStamp? = null,
    ): Long {
        val prev = states[key]
        val incoming = message != null && (prev?.message == null ||
            message.time > prev.message.time ||
            (message.time == 0L && message != prev.message))
        val arrived = if (conversation) incoming else !quiet && signature != prev?.signature
        val alertTime =
            if (prev == null || arrived) postTime else prev.alertTime
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
