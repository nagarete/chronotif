// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTest {
    private data class N(
        val key: String,
        val app: String,
        override val alertTime: Long,
        override val kind: Kind = Kind.NOTIFICATION,
    ) : Meta

    @Test
    fun splitsAtWatermarkNewestFirst() {
        val s = Attention.sections(
            listOf(N("a", "mail", 10), N("b", "chat", 30), N("c", "chat", 20), N("d", "mail", 5)),
            lastCheck = 15,
        )
        assertEquals(listOf("b", "c"), s.fresh.map { it.key })
        assertEquals(listOf("a", "d"), s.earlier.map { it.key })
    }

    @Test
    fun earlierStaysInTimeOrderInsteadOfFoldingByApp() {
        val s = Attention.sections(
            listOf(N("a", "mail", 1), N("b", "chat", 3), N("c", "mail", 4), N("d", "sms", 2)),
            lastCheck = 100,
        )
        assertTrue(s.fresh.isEmpty())
        assertEquals(listOf("c", "b", "d", "a"), s.earlier.map { it.key })
        assertEquals(4, s.earlierCount)
    }

    @Test
    fun earlierStacksOnlyAnAppsNeighbouringCardsThatArrivedCloseTogether() {
        val min = 60_000L
        val cards = listOf(
            N("t3-now", "t3", 240 * min), N("t3-1m", "t3", 239 * min), N("t3-2m", "t3", 238 * min),
            N("insta", "insta", 236 * min),
            N("tt-a", "tiktok", 197 * min), N("tt-b", "tiktok", 197 * min),
            N("tt-c", "tiktok", 196 * min), N("chat", "tiktok", 195 * min), N("tt-d", "tiktok", 194 * min),
            // Four hours before the recent T3 cards, and not next to them: its own stack.
            N("t3-4h-a", "t3", 0), N("t3-4h-b", "t3", 0),
        )
        val stacks = Attention.appStacks(cards, { it.alertTime }, { if (it.key == "chat") null else it.app })
        assertEquals(
            listOf(listOf("t3-now", "t3-1m", "t3-2m"), listOf("insta"), listOf("tt-a", "tt-b", "tt-c"),
                listOf("chat"), listOf("tt-d"), listOf("t3-4h-a", "t3-4h-b")),
            stacks.map { s -> s.map { it.key } },
        )
    }

    @Test
    fun anAppStackSpansAtMostTheWindowFromItsNewestCard() {
        val min = 60_000L
        // Each card is 20 minutes after the next, but the third is 40 behind the stack's newest.
        val cards = listOf(N("a", "mail", 40 * min), N("b", "mail", 20 * min), N("c", "mail", 0))
        assertEquals(listOf(listOf("a", "b"), listOf("c")),
            Attention.appStacks(cards, { it.alertTime }, { it.app }).map { s -> s.map { it.key } })
    }

    @Test
    fun aConversationSplitsAtTheCheckKeepingAtLeastItsLatestMessageNew() {
        assertEquals(2, Attention.newMessages(listOf(5, 20, 30), lastCheck = 15))
        assertEquals(3, Attention.newMessages(listOf(20, 30, 40), lastCheck = 15))
        // Message times come from the sender's clock; a New conversation still shows its latest.
        assertEquals(1, Attention.newMessages(listOf(5, 10), lastCheck = 15))
        assertEquals(0, Attention.newMessages(emptyList(), lastCheck = 15))
    }

    @Test
    fun aConversationsMessagesSplitAtTheCheckWithHiddenOnesLeftOut() {
        // Messages are (id, time); the chat arrived at 30, after the check at 15.
        val msgs = listOf("a" to 5L, "b" to 10L, "c" to 20L, "d" to 30L)
        val chat = N("chat", "chat", 30)
        fun split(item: N, hidden: Set<String>) =
            Attention.split(item, 15, msgs, time = { it.second }, hidden = { it.first in hidden })
        split(chat, emptySet()).let {
            assertEquals(listOf("c", "d"), it.new.map { m -> m.first })
            assertEquals(listOf("a", "b"), it.earlier.map { m -> m.first })
        }
        split(chat, setOf("c", "a")).let {
            assertEquals(listOf("d"), it.new.map { m -> m.first })
            assertEquals(listOf("b"), it.earlier.map { m -> m.first })
        }
        // A checked conversation has nothing New, whatever its message times.
        assertEquals(emptyList<Pair<String, Long>>(), split(N("chat", "chat", 10), emptySet()).new)
        assertEquals(4, split(N("chat", "chat", 10), emptySet()).earlier.size)
    }

    @Test
    fun swipingAwayTheOnlyNewMessageLeavesNothingNewThoughCheckedOnesRemain() {
        val msgs = listOf("seen" to 5L, "new" to 30L)
        val chat = N("chat", "chat", 30)
        fun showsNew(hidden: Set<String>) =
            Attention.showsNew(chat, 15, msgs, time = { it.second }, hidden = { it.first in hidden })
        assertTrue(showsNew(emptySet()))
        val split = Attention.split(chat, 15, msgs, time = { it.second }, hidden = { it.first == "new" })
        assertTrue("The checked message isn't moved up into New", split.new.isEmpty())
        assertEquals(listOf("seen"), split.earlier.map { it.first })
        assertFalse(showsNew(setOf("new")))
        // A New notification without messages shows in New; a checked one doesn't.
        assertTrue(Attention.showsNew(chat, 15, emptyList<Pair<String, Long>>(), { it.second }, { false }))
        assertFalse(Attention.showsNew(N("x", "mail", 10), 15, emptyList<Pair<String, Long>>(), { it.second }, { false }))
    }

    @Test
    fun aChatFirstSeenArrivedWithItsLatestMessageNotWhenItWasReposted() {
        val c = AlertClock()
        // Yesterday's chat, reposted just now (Chronotif restarted, WhatsApp re-alerted it).
        assertEquals(1_000, c.onPosted("chat", 90_000, signature = 1, quiet = false, conversation = true,
            message = MessageStamp(1_000, 1)))
        // A message from a clock running slightly ahead still can't arrive later than its post.
        assertEquals(90_000, AlertClock().onPosted("chat", 90_000, signature = 1, quiet = false, conversation = true,
            message = MessageStamp(95_000, 1)))
        // Plain-text chats have no message time: they arrive when posted, as before.
        assertEquals(90_000, AlertClock().onPosted("chat", 90_000, signature = 1, quiet = false, conversation = true,
            message = MessageStamp(0, 1)))
    }

    @Test
    fun liveKindsAreSeparateAndNeverNew() {
        val call = N("c", "dialer", 40, Kind.CALL)
        val music = N("m", "music", 50, Kind.MEDIA)
        val vpn = N("v", "vpn", 70, Kind.RUNNING)
        val s = Attention.sections(listOf(music, vpn, call, N("x", "chat", 60)), lastCheck = 0)
        assertEquals(listOf("c"), s.calls.map { it.key })
        assertEquals(listOf("m"), s.media.map { it.key })
        assertEquals(listOf("v"), s.running.map { it.key })
        assertEquals(listOf("x"), s.fresh.map { it.key })
        assertTrue(listOf(call, music, vpn).none { Attention.isNew(it, 0) })
    }

    @Test
    fun runningAloneLeavesTheListEmpty() {
        val s = Attention.sections(listOf(N("v", "vpn", 70, Kind.RUNNING)), lastCheck = 0)
        assertTrue(s.isEmpty)
        assertEquals(1, s.running.size)
    }

    @Test
    fun watermarkNeverMovesBack() {
        assertEquals(20, Attention.advance(20, 10))
        assertEquals(30, Attention.advance(20, 30))
    }

    @Test
    fun alertClockOnlyResetsOnRealArrivals() {
        val c = AlertClock()
        assertEquals(100, c.onPosted("k", 100, signature = 1, quiet = false))
        // Same content re-posted (e.g. timestamp refresh): keeps original arrival.
        assertEquals(100, c.onPosted("k", 200, signature = 1, quiet = false))
        // Non-conversation content marked quiet: still not a new arrival.
        assertEquals(100, c.onPosted("k", 300, signature = 2, quiet = true))
        // New content, not quiet: arrives again (a new message in an existing chat).
        assertEquals(400, c.onPosted("k", 400, signature = 3, quiet = false))
        c.onRemoved("k")
        assertEquals(500, c.onPosted("k", 500, signature = 3, quiet = true))
    }

    @Test
    fun silentMessagesAdvanceAttentionEvenWhenTheBodyRepeats() {
        val c = AlertClock()
        fun post(time: Long, message: MessageStamp?) =
            c.onPosted("chat", time, signature = 1, quiet = true, conversation = true, message = message)
        // First seen: it arrived with its message.
        assertEquals(10, post(100, MessageStamp(10, 1)))
        assertEquals(10, post(200, MessageStamp(10, 1)))
        assertEquals(300, post(300, MessageStamp(20, 1)))
        // Trimming history or appending an outgoing reply must not move the arrival time.
        assertEquals(300, post(400, MessageStamp(10, 1)))
        assertEquals(300, post(500, null))
        assertEquals(300, post(600, MessageStamp(20, 1)))
    }

    @Test
    fun plainConversationTextChangesCountButRefreshesDoNot() {
        val c = AlertClock()
        fun post(time: Long, signature: Int) = c.onPosted("chat", time, signature,
            quiet = true, conversation = true, message = MessageStamp(0, signature))
        assertEquals(100, post(100, 1))
        assertEquals(100, post(200, 1))
        assertEquals(300, post(300, 2))
    }

    @Test
    fun relativeTime() {
        val day = 86_400_000L
        val today = 10 * day
        val now = today + 12 * 3_600_000
        assertEquals(RelTime.Label.Now, RelTime.of(now, now - 5_000, today))
        assertEquals(RelTime.Label.Minutes(5), RelTime.of(now, now - 5 * 60_000, today))
        assertEquals(RelTime.Label.Hours(3), RelTime.of(now, now - 3 * 3_600_000, today))
        assertEquals(RelTime.Label.Yesterday, RelTime.of(now, today - 8 * 3_600_000, today))
        assertEquals(RelTime.Label.Older, RelTime.of(now, today - 3 * day, today))
    }

    @Test
    fun aNotificationBackFromASnoozeArrivesWhenItIsPostedAgain() {
        // Android reposts it as it was; Chronotif passes the time it came back.
        assertEquals(90_000, AlertClock().onPosted("chat", 90_000, signature = 1, quiet = true, conversation = true,
            message = MessageStamp(1_000, 1), returned = true))
        assertEquals(90_000, AlertClock().onPosted("mail", 90_000, signature = 1, quiet = true, returned = true))
    }

    @Test
    fun snoozeTimesAndTomorrowMorningAfterMidnight() {
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        fun at(day: Int, hour: Int, minute: Int = 0) =
            java.time.LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        val now = at(20, 14, 30)
        assertEquals(now + 15 * 60_000, Snooze.until(SnoozeFor.MINUTES_15, now, zone))
        assertEquals(now + 3 * 3_600_000, Snooze.until(SnoozeFor.HOURS_3, now, zone))
        assertEquals(at(21, 8), Snooze.until(SnoozeFor.TOMORROW_MORNING, now, zone))
        assertEquals(at(21, 8), Snooze.until(SnoozeFor.TOMORROW_MORNING, at(20, 23, 50), zone))
        // At 1 a.m. "tomorrow morning" is the one about to come, not the day after.
        assertEquals(at(21, 8), Snooze.until(SnoozeFor.TOMORROW_MORNING, at(21, 1), zone))
        assertEquals(at(22, 8), Snooze.until(SnoozeFor.TOMORROW_MORNING, at(21, 4), zone))
    }

    @Test
    fun aPickedSnoozeTimeIsTheNextTimeItComesRound() {
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        fun at(day: Int, hour: Int, minute: Int = 0) =
            java.time.LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
        val now = at(20, 14, 30)
        assertEquals(at(20, 18), Snooze.at(java.time.LocalTime.of(18, 0), now, zone))
        assertEquals("Already past today: tomorrow", at(21, 9), Snooze.at(java.time.LocalTime.of(9, 0), now, zone))
        assertEquals("Right now is tomorrow's", at(21, 14, 30), Snooze.at(java.time.LocalTime.of(14, 30), now, zone))
        assertEquals(java.time.LocalTime.of(15, 30), Snooze.suggested(now, zone))
        assertEquals(java.time.LocalTime.of(15, 35), Snooze.suggested(at(20, 14, 31), zone))
        assertEquals(java.time.LocalTime.of(0, 0), Snooze.suggested(at(20, 22, 58), zone))
    }
}
