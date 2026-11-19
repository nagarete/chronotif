// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatchTest {
    private fun search(query: String, vararg names: String) = AppMatch.search(names.toList(), query) { it }

    @Test fun tiersAreStartThenWordStartThenAnywhere() {
        assertEquals(0, AppMatch.tier("TikTok", "tik"))
        assertEquals(1, AppMatch.tier("YouTube Music", "music"))
        assertEquals(2, AppMatch.tier("TikTok", "tok"))
        assertNull(AppMatch.tier("TikTok", "talk"))
        assertNull("An empty query matches nothing", AppMatch.tier("TikTok", "  "))
    }

    @Test fun caseAndAccentsDoNotMatter() {
        assertEquals(0, AppMatch.tier("Café Reader", "CAFE"))
        assertEquals(0, AppMatch.tier("cafe", "Café"))
    }

    @Test fun betterTierFirstThenAlphabetical() {
        assertEquals(
            listOf("Maps", "Messages", "Google Maps", "Gmail"),
            search("m", "Gmail", "Google Maps", "Messages", "Maps"),
        )
    }

    @Test fun suggestionsAreTheNewestNotifyingAppsOnceEachSkippingUnlaunchable() {
        val last = mapOf("chat" to 500L, "mail" to 900L, "gone" to 1000L, "bank" to 100L, "news" to 700L, "shop" to 300L)
        assertEquals(
            listOf("mail", "news", "chat", "shop"),
            AppMatch.suggestions(last, launchable = { it != "gone" }),
        )
    }
}
