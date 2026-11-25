// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The Lock-screen shortcut's Do Not Disturb. From Android 15 an app's calls turn only its own mode
 * on and off, so a mode someone else turned on stays on; the shortcut says so and sends the user
 * to Android's settings rather than claiming it's off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuietModeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setup() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @After fun cleanup() {
        ScheduleKeepsItOn.on = false
    }

    @Test fun `holding turns it on, and holding again turns Chronotif's own off`() {
        assertEquals(QuietMode.Toggle.CHANGED, QuietMode.toggle(context))
        assertTrue(QuietMode.on(context))
        assertEquals(QuietMode.Toggle.CHANGED, QuietMode.toggle(context))
        assertFalse(QuietMode.on(context))
    }

    @Test fun `without access it says so`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        assertEquals(QuietMode.Toggle.NO_ACCESS, QuietMode.toggle(context))
        assertFalse(QuietMode.on(context))
    }

    @Test
    @Config(shadows = [ScheduleKeepsItOn::class])
    fun `a mode another owner keeps on shows as on and isn't claimed off`() {
        ScheduleKeepsItOn.on = true
        assertTrue("Any mode lights the shortcut", QuietMode.on(context))
        assertEquals(QuietMode.Toggle.NOT_OURS, QuietMode.toggle(context))
        assertTrue(QuietMode.on(context))
    }

    @Test
    @Config(shadows = [ScheduleKeepsItOn::class])
    fun `with both on, holding turns Chronotif's own off and points at the other`() {
        QuietMode.toggle(context)
        ScheduleKeepsItOn.on = true
        assertEquals(QuietMode.Toggle.NOT_OURS, QuietMode.toggle(context))
        ScheduleKeepsItOn.on = false
        assertFalse("Chronotif's own went off", QuietMode.on(context))
    }

    @Test fun `Android's settings for modes open`() {
        assertTrue(QuietMode.openSettings(context))
        assertEquals(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, shadowOf(context as Application).nextStartedActivity.action)
    }

    /** A schedule's mode is on: the app's own calls can't turn the phone's filter back to all. */
    @Implements(NotificationManager::class)
    class ScheduleKeepsItOn : ShadowNotificationManager() {
        companion object {
            var on = false
        }

        @Implementation
        override fun getCurrentInterruptionFilter(): Int =
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else super.getCurrentInterruptionFilter()
    }
}
