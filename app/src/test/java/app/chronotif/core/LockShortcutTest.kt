// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockShortcutTest {
    @Test
    fun flashlightOnTheLeftAndCameraOnTheRightByDefault() {
        assertEquals(LockShortcut.FLASHLIGHT, LockShortcut.of(null, LockShortcut.DEFAULT_LEFT))
        assertEquals(LockShortcut.CAMERA, LockShortcut.of(null, LockShortcut.DEFAULT_RIGHT))
    }

    @Test
    fun aSavedChoiceIsReadBackAndAnUnknownOneFallsBack() {
        LockShortcut.entries.forEach { assertEquals(it, LockShortcut.of(it.key, LockShortcut.NONE)) }
        assertEquals(LockShortcut.CAMERA, LockShortcut.of("video", LockShortcut.CAMERA))
    }

    @Test
    fun oneThingCantSitInBothCornersExceptNoneAndApps() {
        val beside = LockShortcut.choicesBeside(LockShortcut.FLASHLIGHT)
        assertFalse(LockShortcut.FLASHLIGHT in beside)
        assertTrue(LockShortcut.CAMERA in beside)
        assertTrue(LockShortcut.NONE in LockShortcut.choicesBeside(LockShortcut.NONE))
        assertTrue(LockShortcut.APP in LockShortcut.choicesBeside(LockShortcut.APP))
    }
}
