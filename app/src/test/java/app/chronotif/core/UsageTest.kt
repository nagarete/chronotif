// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test
    fun cardCountsAreRough() {
        assertEquals("0-10", Usage.cardBucket(0))
        assertEquals("0-10", Usage.cardBucket(10))
        assertEquals("10-50", Usage.cardBucket(11))
        assertEquals("10-50", Usage.cardBucket(50))
        assertEquals("50+", Usage.cardBucket(51))
    }

    @Test
    fun countsSurviveBeingKept() {
        val kept = Usage.encodeCounts(mapOf("panel_opened" to 3, "snoozed" to 1, "cleared" to 0))
        assertEquals(mapOf("panel_opened" to 3, "snoozed" to 1), Usage.decodeCounts(kept))
        assertEquals(emptyMap<String, Int>(), Usage.decodeCounts(null))
        assertEquals(emptyMap<String, Int>(), Usage.decodeCounts("garbage;=1;x=y"))
    }

    @Test
    fun speedsSurviveBeingKept() {
        val s = listOf(Usage.Speed(1_000L, 120L, "10-50", kept = true, pulled = false), Usage.Speed(2_000L, 480L, "50+", false, true))
        assertEquals(s, Usage.decodeSpeeds(Usage.encodeSpeeds(s)))
        assertEquals(emptyList<Usage.Speed>(), Usage.decodeSpeeds(""))
    }

    @Test
    fun aCrashSendsItsTypesAndFramesButNeverItsMessage() {
        val e = IllegalStateException("reply to Alice: see you at 6", RuntimeException("Bob's bank code 1234"))
        val props = Usage.crashProps(e)
        assertEquals("java.lang.IllegalStateException", props["exception"])
        assertEquals("java.lang.RuntimeException", props["causes"])
        assertTrue(props["frames"].toString().contains("UsageTest.aCrashSendsItsTypesAndFramesButNeverItsMessage"))
        val all = props.values.joinToString(" ")
        assertFalse(all.contains("Alice"))
        assertFalse(all.contains("1234"))
        assertTrue(props["frames"].toString().lines().size <= Usage.MAX_FRAMES)
    }

    @Test
    fun namesTheMakersSystem() {
        fun sys(vararg props: Pair<String, String>) = Usage.system { props.toMap()[it] }
        assertEquals("HyperOS 3.0", sys("ro.mi.os.version.name" to "OS3.0", "ro.miui.ui.version.name" to "V816"))
        assertEquals("HyperOS 2.0", sys("ro.mi.os.version.name" to "OS2.0.200.0.VNXMIXM"))
        assertEquals("MIUI 14", sys("ro.miui.ui.version.name" to "V140"))
        assertEquals("MIUI 12.5", sys("ro.miui.ui.version.name" to "V125"))
        assertEquals("MIUI 10", sys("ro.miui.ui.version.name" to "V10"))
        assertEquals("One UI 6.1", sys("ro.build.version.oneui" to "60101"))
        assertEquals("ColorOS 14", sys("ro.build.version.oplusrom" to "V14.0.0"))
        assertEquals("Android", sys())
        assertEquals("Android", sys("ro.mi.os.version.name" to " "))
    }

    @Test
    fun aBatchSentTwiceIsRecordedOnce() {
        val a = Usage.event("install", "daily", 1_000L, emptyMap())
        val b = Usage.event("install", "daily", 1_000L, mapOf("n_panel_opened" to 4))
        assertEquals(a.uuid, b.uuid)
        assertNotEquals(a.uuid, Usage.event("install", "daily", 2_000L, emptyMap()).uuid)
        assertNotEquals(a.uuid, Usage.event("install", "daily", 1_000L, emptyMap(), seq = 1).uuid)
    }

    @Test
    fun theBatchSaysNoPersonProfileAndNoIpLookup() {
        val e = Usage.event("id-1", "daily", 0L, mapOf("system" to "One \"UI\"\n6", "days" to 2, "on" to true, "none" to null))
        val body = Usage.batch("phc_key", "id-1", listOf(e))
        assertTrue(body.startsWith("{\"api_key\":\"phc_key\",\"batch\":[{\"event\":\"daily\""))
        assertTrue(body.contains("\"timestamp\":\"1970-01-01T00:00:00Z\""))
        assertTrue(body.contains("\"system\":\"One \\\"UI\\\"\\n6\""))
        assertTrue(body.contains("\"days\":2"))
        assertTrue(body.contains("\"on\":true"))
        assertTrue(body.contains("\"none\":null"))
        assertTrue(body.contains("\"distinct_id\":\"id-1\""))
        assertTrue(body.contains("\"\$process_person_profile\":false"))
        assertTrue(body.contains("\"\$geoip_disable\":true"))
        assertTrue(body.endsWith("}}]}"))
    }

    @Test
    fun quotesControlCharacters() {
        assertEquals("\"a\\u0001b\\\\c\\t\"", Usage.quote("a\u0001b\\c\t"))
    }
}
