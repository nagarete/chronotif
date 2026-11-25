// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackMessageTest {
    @Test fun `the body carries what was filled in, trimmed`() {
        val body = FeedbackMessage.body(FeedbackMessage.Kind.IDEA, "  Make it \"blue\"\nplease ", " me@example.com ",
            listOf("Chronotif" to "0.3.1 (nightly)", "Phone" to "Xiaomi 2201117PG"))
        assertEquals(
            """{"source":"app","kind":"idea","text":"Make it \"blue\"\nplease","contact":"me@example.com",""" +
                """"details":{"Chronotif":"0.3.1 (nightly)","Phone":"Xiaomi 2201117PG"}}""",
            body,
        )
    }

    @Test fun `what's left empty is left out`() {
        assertEquals("""{"source":"app","text":"Hi"}""", FeedbackMessage.body(null, "Hi", "  ", null))
    }

    @Test fun `only a message within the limits is ready`() {
        assertFalse(FeedbackMessage.ready("   ", ""))
        assertTrue(FeedbackMessage.ready("x".repeat(FeedbackMessage.MAX_TEXT), ""))
        assertFalse(FeedbackMessage.ready("x".repeat(FeedbackMessage.MAX_TEXT + 1), ""))
        assertFalse(FeedbackMessage.ready("Hi", "x".repeat(FeedbackMessage.MAX_CONTACT + 1)))
    }
}
