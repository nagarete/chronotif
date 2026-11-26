// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandleGestureTest {
    @Test
    fun aNewInstallTapsForSearchSwipesDownForThePanelAndHoldsToSleep() {
        assertEquals(HandleAction.SEARCH, HandleGesture.TAP.default)
        assertEquals(HandleAction.PANEL, HandleGesture.SWIPE_DOWN.default)
        assertEquals(HandleAction.SEARCH, HandleGesture.SWIPE_INWARD.default)
        assertEquals(HandleAction.SLEEP, HandleGesture.HOLD.default)
        assertEquals(HandleAction.NONE, HandleGesture.DOUBLE_TAP.default)
        assertEquals(HandleAction.NONE, HandleGesture.SWIPE_UP.default)
    }

    @Test
    fun anOlderInstallKeepsWhatTheHandleDidBeforeGesturesCouldBePicked() {
        assertEquals(HandleAction.PANEL, HandleGesture.TAP.before)
        assertEquals(HandleAction.PANEL, HandleGesture.SWIPE_DOWN.before)
        assertEquals(HandleAction.PANEL, HandleGesture.SWIPE_INWARD.before)
        assertEquals(HandleAction.SEARCH, HandleGesture.HOLD.before)
        assertEquals(HandleAction.NONE, HandleGesture.DOUBLE_TAP.before)
        assertEquals(HandleAction.NONE, HandleGesture.SWIPE_UP.before)
    }

    @Test
    fun aSavedActionIsReadBackAndAnUnknownOneFallsBack() {
        HandleAction.entries.forEach { assertEquals(it, HandleAction.of(it.key, HandleAction.NONE)) }
        assertEquals(HandleAction.PANEL, HandleAction.of("screenshot", HandleAction.PANEL))
    }

    @Test
    fun theShortcutActionsAreTheLockScreenShortcuts() {
        assertEquals(LockShortcut.FLASHLIGHT, HandleAction.FLASHLIGHT.shortcut)
        assertEquals(LockShortcut.APP, HandleAction.APP.shortcut)
        assertNull(HandleAction.SLEEP.shortcut)
        assertNull(HandleAction.NONE.shortcut)
        LockShortcut.entries.forEach { assertEquals(it.key, HandleAction.of(it.key, HandleAction.PANEL).key) }
    }

    @Test
    fun aSwipeIsNamedByItsLongerDirection() {
        val t = 20f
        assertNull("not far enough", HandleGesture.swipe(10f, 15f, right = true, trigger = t))
        assertEquals(HandleGesture.SWIPE_DOWN, HandleGesture.swipe(-15f, 30f, right = true, trigger = t))
        assertEquals(HandleGesture.SWIPE_UP, HandleGesture.swipe(5f, -25f, right = false, trigger = t))
        assertEquals(HandleGesture.SWIPE_INWARD, HandleGesture.swipe(-30f, 10f, right = true, trigger = t))
        assertEquals(HandleGesture.SWIPE_INWARD, HandleGesture.swipe(30f, -10f, right = false, trigger = t))
        assertNull("outward has no room", HandleGesture.swipe(30f, 10f, right = true, trigger = t))
    }
}
