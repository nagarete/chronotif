// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelEmptyTapTest {
    @Test fun tapBelowLastCardCloses() {
        assertTrue(PanelView.isEmptySpaceTap(true, true, 2f, -2f, 10f))
        assertFalse(PanelView.isEmptySpaceTap(false, true, 0f, 0f, 10f))
        assertFalse(PanelView.isEmptySpaceTap(true, false, 0f, 0f, 10f))
    }

    @Test fun dragsAreNotMistakenForTaps() {
        assertFalse(PanelView.isEmptySpaceTap(true, true, 15f, 0f, 10f))
        assertFalse(PanelView.isEmptySpaceTap(true, true, 0f, -15f, 10f))
        assertTrue(PanelView.isEmptySpaceTap(true, true, -10f, 10f, 10f))
    }
}
