// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionTest {
    private data class N(
        val key: String,
        override val stackId: String,
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
        assertEquals(listOf("mail"), s.earlier.map { it.id })
        assertEquals(listOf("a", "d"), s.earlier[0].items.map { it.key })
    }

    @Test
    fun earlierStacksOrderedByNewestMember() {
        val s = Attention.sections(
            listOf(N("a", "mail", 1), N("b", "chat", 3), N("c", "mail", 4), N("d", "sms", 2)),
            lastCheck = 100,
        )
        assertTrue(s.fresh.isEmpty())
        assertEquals(listOf("mail", "chat", "sms"), s.earlier.map { it.id })
        assertEquals(4, s.earlierCount)
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
}
