// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintSpotTest {
    @Test
    fun readsWhereThePhoneSaysTheSensorIs() {
        // A 17T Pro: Xiaomi's top-left corner at 535,2413 on a 2772 px panel.
        assertEquals(2413f / 2772, FingerprintSpot.fromXiaomi("535,2413", 2772)!!, 1e-6f)
        // AOSP: centre 1200 with radius 100 starts at 1100.
        assertEquals(0.5f, FingerprintSpot.fromUdfpsProps(intArrayOf(540, 1200, 100), 2200)!!, 1e-6f)
        assertNull("HyperOS leaves the AOSP array empty", FingerprintSpot.fromUdfpsProps(intArrayOf(), 2772))
        assertNull("and keeps its properties from apps", FingerprintSpot.fromXiaomi("", 2772))
    }

    @Test
    fun aFittedTouchWinsThenThePhoneThenTheDefault() {
        assertEquals(0.9f, FingerprintSpot.top(0.9f, 0.87f, present = true))
        assertEquals(0.87f, FingerprintSpot.top(null, 0.87f, present = true))
        assertEquals(FingerprintSpot.DEFAULT_TOP, FingerprintSpot.top(null, null, present = true))
        assertNull("No sensor under the screen, nothing moves", FingerprintSpot.top(null, null, present = false))
        assertNull("A spot in the top half isn't a bottom sensor", FingerprintSpot.top(0.2f, null, present = false))
    }

    @Test
    fun aTouchOnTheSensorFitsItsTopEdge() {
        // A touch at the sensor's centre: the top is half a sensor above it.
        assertEquals((2518f - 110) / 2772, FingerprintSpot.fitted(2518f, 2772, 110f)!!, 1e-6f)
        assertNull("too high on the screen for a fingerprint sensor", FingerprintSpot.fitted(600f, 2772, 110f))
    }

    @Test
    fun raisesTapToUnlockUntilItsIconIsOnTheSensor() {
        // The icon's centre 120 px below the sensor's: 120 px more padding under it.
        assertEquals(158, FingerprintSpot.hintPadding(current = 38, icon = 2620, sensor = 2500, min = 38))
        // Already above it: never less than the usual padding.
        assertEquals(38, FingerprintSpot.hintPadding(current = 38, icon = 2400, sensor = 2500, min = 38))
    }
}
