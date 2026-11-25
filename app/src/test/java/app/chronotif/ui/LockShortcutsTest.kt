// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import app.chronotif.Prefs
import app.chronotif.core.LockShortcut
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LockShortcutsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private fun listensToQuiet() = shadowOf(context as Application).registeredReceivers
        .any { it.intentFilter.hasAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) }

    /** Idles the main thread until [done], while work on the background lane finishes. */
    private fun idleUntil(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    @Test fun withoutAFlashlightOrDoNotDisturbSlotAWakeListensToNeither() {
        Prefs.lockShortcutLeft = LockShortcut.CAMERA
        Prefs.lockShortcutRight = LockShortcut.NONE
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shortcuts = LockShortcuts(activity, practice = false) { it() }
        shortcuts.start()
        // Let anything handed to the background lane run.
        repeat(20) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        assertFalse("No Do Not Disturb slot, so nothing listens for it", listensToQuiet())
        shortcuts.stop()
    }

    @Test fun aDoNotDisturbSlotLightsUpWhileItIsOn() {
        Prefs.lockShortcutLeft = LockShortcut.DO_NOT_DISTURB
        Prefs.lockShortcutRight = LockShortcut.NONE
        val nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shortcuts = LockShortcuts(activity, practice = false) { it() }
        shortcuts.start()
        idleUntil { shortcuts.left.isActivated && listensToQuiet() }
        assertTrue("Do Not Disturb is read off the main thread, then shows", shortcuts.left.isActivated)
        assertTrue("And changes to it are listened for", listensToQuiet())
        shortcuts.stop()
        idleUntil { !listensToQuiet() }
        assertFalse("Stopping lets go of it", listensToQuiet())
    }
}
