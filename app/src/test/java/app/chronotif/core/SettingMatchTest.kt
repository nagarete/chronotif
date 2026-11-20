// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingMatchTest {
    private class S(val title: String, val other: String = "") {
        val doc = SettingMatch.Doc(title, other)
    }

    private val settings = listOf(
        S("Edge handle", "Edge handle"),
        S("Usual colour", "Edge handle Colour color"),
        S("When something is new", "Edge handle Colour color light"),
        S("Clock size", "Panel Header font big"),
        S("Swipe to clear", "Panel dismiss"),
        S("Stacks", "Tips Tap an open stack's name to close it"),
    )

    private fun titles(q: String) = SettingMatch.search(settings, q, 10) { it.doc }.map { it.title }

    @Test fun `title start beats a later title word, which beats other words`() {
        assertEquals(listOf("Usual colour", "When something is new"), titles("col"))
        assertEquals(listOf("Clock size", "Stacks"), titles("clo"))
    }

    @Test fun `every typed word must start a word, in any order`() {
        assertEquals(listOf("Clock size"), titles("size clock"))
        assertEquals(listOf("Clock size"), titles("header big"))
        assertEquals(emptyList<String>(), titles("clock colour"))
    }

    @Test fun `American spelling finds it through its other words`() {
        assertEquals(listOf("Usual colour", "When something is new"), titles("color"))
    }

    @Test fun `case and accents don't matter`() {
        assertEquals(listOf("Swipe to clear"), titles("SWÎPE"))
    }

    @Test fun `the middle of a word only counts in the title`() {
        assertEquals(listOf("Swipe to clear"), titles("wipe"))
        assertEquals(emptyList<String>(), titles("ismiss"))
    }

    @Test fun `blank query matches nothing`() {
        assertEquals(emptyList<String>(), titles("  "))
        assertNull(SettingMatch.tier(settings[0].doc, SettingMatch.Query("")))
    }

    @Test fun `limit keeps the best`() {
        assertEquals(listOf("Edge handle"), SettingMatch.search(settings, "e", 1) { it.doc }.map { it.title })
    }
}
