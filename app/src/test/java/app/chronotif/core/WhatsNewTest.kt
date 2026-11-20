// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import app.chronotif.core.WhatsNew.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private val releases = listOf(
        Release("0.2.7", "Next", listOf("Settings have a new look.", "Search settings.")),
        Release("0.2.6", "Search", listOf("Pull down to search.")),
        Release("0.2.5", "Older", listOf("Snooze.")),
    )

    private fun show(current: String, seen: Set<String>?) =
        WhatsNew.unseen(releases, current, seen).map { it.version to it.notes }

    @Test fun `a nightly shows only the lines added since the last one`() {
        val seen = setOf(WhatsNew.key("Settings have a new look."), WhatsNew.key("Pull down to search."), WhatsNew.key("Snooze."))
        assertEquals(listOf("0.2.7" to listOf("Search settings.")), show("0.2.7-nightly.12", seen))
    }

    @Test fun `nothing new shows nothing`() {
        assertTrue(show("0.2.7-nightly.12", WhatsNew.allKeys(releases, "0.2.7")).isEmpty())
    }

    @Test fun `notes for a version not out yet are left out`() {
        assertEquals(listOf("0.2.6" to listOf("Pull down to search.")), show("0.2.6", WhatsNew.allKeys(releases, "0.2.5")))
        assertEquals(setOf(WhatsNew.key("Pull down to search."), WhatsNew.key("Snooze.")), WhatsNew.allKeys(releases, "0.2.6"))
    }

    @Test fun `skipping versions shows each, newest first`() {
        assertEquals(
            listOf("0.2.7" to listOf("Settings have a new look.", "Search settings."), "0.2.6" to listOf("Pull down to search.")),
            show("0.2.7", WhatsNew.allKeys(releases, "0.2.5")),
        )
    }

    @Test fun `with no record, only this version's notes show`() {
        assertEquals(listOf("0.2.7" to listOf("Settings have a new look.", "Search settings.")), show("0.2.7-debug", null))
        // A version the notes don't cover yet shows nothing rather than an older version's notes.
        assertTrue(show("0.2.8", null).isEmpty())
    }

    @Test fun `a reworded line counts as new`() {
        val seen = WhatsNew.allKeys(releases, "0.2.7") - WhatsNew.key("Search settings.")
        assertEquals(listOf("0.2.7" to listOf("Search settings.")), show("0.2.7", seen))
    }
}
