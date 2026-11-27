// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Auto-update is on unless turned off, and Update available notifies once per build (ADR 0025). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateAvailableTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val saved get() = context.getSharedPreferences("chronotif", Context.MODE_PRIVATE)
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        saved.edit().clear().commit()
        Prefs.init(context)
        nm.cancelAll()
    }

    @Test
    fun autoUpdateIsOnForAnInstallThatNeverChoseAndItsNoticeShows() {
        assertTrue(Prefs.autoUpdate)
        assertFalse(Prefs.autoUpdateNoticed)
    }

    @Test
    fun someoneWhoTurnedAutoUpdateOffKeepsItOffAndIsNotToldAgain() {
        saved.edit().putBoolean("auto_update", false).commit()
        assertFalse(Prefs.autoUpdate)
        assertTrue(Prefs.autoUpdateNoticed)
    }

    @Test
    fun eachBuildIsNotifiedOnceSoSwipingItAwayIsEnough() {
        Updater.offer(context, "9.0.0")
        assertEquals(1, shadowOf(nm).allNotifications.size)
        nm.cancelAll()

        Updater.offer(context, "9.0.0")
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals("9.0.0", Updater.available)

        Updater.offer(context, "9.0.1-nightly.1")
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun blockedNotificationsDontUseUpTheBuildsNotice() {
        shadowOf(nm).setNotificationsEnabled(false)
        Updater.offer(context, "9.0.0")
        assertNull(Prefs.updateOffered)

        shadowOf(nm).setNotificationsEnabled(true)
        Updater.offer(context, "9.0.0")
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun aVersionNoNewerThanThisOneIsNotOffered() {
        Prefs.updateAvailable = "0.0.1"
        assertNull(Updater.available)
    }
}
