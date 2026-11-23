// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * The attention model, free of Android types so it can be unit-tested.
 *
 * Every notification carries an [Meta.alertTime]: when it last *arrived* (first post, or an
 * update that genuinely brought new content). A single watermark, the time of the user's last
 * meaningful check, splits notifications into NEW (arrived after it) and EARLIER (already seen).
 * A Priority app's cards sit in PRIORITY, above NEW, until they're opened, and a Quiet app's go
 * straight to EARLIER (ADR 0019): each app's [AppTier] moves the watermark its cards are split at
 * ([since]). Every section is in time order. In EARLIER and PRIORITY, one app's cards that sit next
 * to each other and arrived close together fold into a stack ([appStacks]). A conversation's messages
 * are split the same way as notifications when it's drawn ([newMessages]).
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

/** What the user made an app: a Priority app, a Quiet app, or neither (every app, until picked). */
enum class Tier { PRIORITY, NORMAL, QUIET }

/**
 * An app's [tier], and the time it took effect: a Priority app's cards that arrived after [since] go
 * to PRIORITY, and a Normal app's after it count as New, so cards already checked never come back up.
 */
data class AppTier(val tier: Tier, val since: Long = 0L) {
    companion object {
        val NORMAL = AppTier(Tier.NORMAL)
    }
}

class Sections<T>(
    val calls: List<T>,
    val media: List<T>,
    /** Priority apps' cards not yet opened, above NEW. */
    val priority: List<T>,
    val fresh: List<T>,
    val earlier: List<T>,
    val running: List<T>,
) {
    /** Running work alone doesn't make the list non-empty in any way worth announcing. */
    val isEmpty get() = calls.isEmpty() && media.isEmpty() && priority.isEmpty() && fresh.isEmpty() && earlier.isEmpty()
    val earlierCount get() = earlier.size
}

object Attention {

    /**
     * Newest first everywhere, EARLIER included; [appStacks] folds EARLIER and PRIORITY only where that
     * keeps time order. [tier] is each item's app's setting, and [openedAt] when it was last opened.
     */
    fun <T : Meta> sections(
        items: Collection<T>, lastCheck: Long,
        tier: (T) -> AppTier = { AppTier.NORMAL }, openedAt: (T) -> Long = { 0L },
    ): Sections<T> {
        val byRecent = items.sortedByDescending { it.alertTime }
        val calls = ArrayList<T>()
        val media = ArrayList<T>()
        val running = ArrayList<T>()
        val priority = ArrayList<T>()
        val fresh = ArrayList<T>()
        val earlier = ArrayList<T>()
        for (item in byRecent) when (item.kind) {
            Kind.CALL -> calls += item
            Kind.MEDIA -> media += item
            Kind.RUNNING -> running += item
            Kind.NOTIFICATION -> {
                val t = tier(item)
                when {
                    item.alertTime <= since(t, lastCheck, openedAt(item)) -> earlier += item
                    t.tier == Tier.PRIORITY -> priority += item
                    else -> fresh += item
                }
            }
        }
        return Sections(calls, media, priority, fresh, earlier, running)
    }

    /**
     * The time a notification's arrivals are split at: after it, its cards (or a conversation's
     * messages) sit in PRIORITY for a Priority app and in NEW otherwise; at or before it, in EARLIER.
     * A Priority app's split moves when the notification is opened ([openedAt]), not at a check. A
     * Quiet app's cards are never New.
     */
    fun since(tier: AppTier, lastCheck: Long, openedAt: Long = 0L): Long = when (tier.tier) {
        Tier.PRIORITY -> maxOf(tier.since, openedAt)
        Tier.NORMAL -> maxOf(lastCheck, tier.since)
        Tier.QUIET -> Long.MAX_VALUE
    }

    /**
     * [from] made [to] at [now]. Nothing already checked comes back up: a Normal app made Priority
     * takes its New cards along, Earlier stays; leaving Priority, or going Quiet, counts as a check of
     * that app's cards.
     */
    fun retier(from: AppTier, to: Tier, lastCheck: Long, now: Long): AppTier = when {
        from.tier == to -> from
        to == Tier.PRIORITY -> AppTier(to, if (from.tier == Tier.NORMAL) since(from, lastCheck) else now)
        else -> AppTier(to, now)
    }

    /** How far behind an app stack's newest card another card from that app may be and still join it. */
    const val STACK_WINDOW = 30 * 60_000L

    /**
     * Folds EARLIER's or PRIORITY's [cards] (newest first) into app stacks (ADR 0004). A card joins the stack above it
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

    /**
     * Whether [item] arrived since the last check, for whatever announces arrivals (the lock-screen
     * view, the edge handle's count): a New card, or a Priority card that came in since. A Quiet app's
     * never did.
     */
    fun isNew(item: Meta, lastCheck: Long, tier: AppTier = AppTier.NORMAL) =
        item.kind == Kind.NOTIFICATION && tier.tier != Tier.QUIET && item.alertTime > maxOf(lastCheck, tier.since)

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
