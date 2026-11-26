// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import app.chronotif.core.SupportAsk.WAIT
import app.chronotif.core.SupportAsk.WAIT_TAPPED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportAskTest {
    private val since = 1_000_000_000L

    @Test fun `the first week after the first run is quiet`() {
        assertFalse(SupportAsk.due(since + WAIT - 1, since, 0L, tapped = false))
        assertTrue(SupportAsk.due(since + WAIT, since, 0L, tapped = false))
    }

    @Test fun `it comes again a week after it last showed, with no end`() {
        val shown = since + 10 * WAIT
        assertFalse(SupportAsk.due(shown + WAIT - 1, since, shown, tapped = false))
        assertTrue(SupportAsk.due(shown + WAIT, since, shown, tapped = false))
    }

    @Test fun `after Support was tapped it comes every three weeks`() {
        val shown = since + WAIT
        assertFalse(SupportAsk.due(shown + WAIT, since, shown, tapped = true))
        assertFalse(SupportAsk.due(shown + WAIT_TAPPED - 1, since, shown, tapped = true))
        assertTrue(SupportAsk.due(shown + WAIT_TAPPED, since, shown, tapped = true))
    }

    @Test fun `weeks away from Settings add up to one sheet`() {
        val shown = since + WAIT
        // Due once; showing it moves lastShown to now, so it isn't due again straight after.
        val later = shown + 5 * WAIT
        assertTrue(SupportAsk.due(later, since, shown, tapped = false))
        assertFalse(SupportAsk.due(later + 1, since, later, tapped = false))
    }

    @Test fun `nothing before the first run is recorded`() {
        assertFalse(SupportAsk.due(since, 0L, 0L, tapped = false))
    }
}
