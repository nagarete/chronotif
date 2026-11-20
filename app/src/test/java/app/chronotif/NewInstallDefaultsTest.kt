// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.content.Context
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

/** New installs get 0.2.7's defaults; an install from before keeps the ones it had (ADR 0015). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NewInstallDefaultsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val saved get() = context.getSharedPreferences("chronotif", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        saved.edit().clear().commit()
    }

    private fun updatedFromAnOlderVersion() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
            firstInstallTime = 1_000L
            lastUpdateTime = 2_000L
        }
    }

    @Test
    fun aNewInstallShowsTheLockScreenViewWheneverThereAreNotificationsAndConvertsCurrencies() {
        Prefs.init(context)
        assertEquals(Prefs.LOCK_ALWAYS, Prefs.lockMode)
        assertTrue(Prefs.currencyRates)
    }

    @Test
    fun anUpdatedInstallKeepsTheDefaultsItHad() {
        updatedFromAnOlderVersion()
        Prefs.init(context)
        assertEquals(Prefs.LOCK_WHEN_NEW, Prefs.lockMode)
        assertFalse("Currency rates were opt-in before", Prefs.currencyRates)
    }

    @Test
    fun anInstallWithSettingsSavedCountsAsOldEvenWithoutAnUpdate() {
        // A restored backup, say: settings exist before the first start.
        saved.edit().putLong("last_check", 5_000L).commit()
        Prefs.init(context)
        assertEquals(Prefs.LOCK_WHEN_NEW, Prefs.lockMode)
        assertFalse(Prefs.currencyRates)
    }

    @Test
    fun whatTheUserPickedIsKeptAndTheOldDefaultsAreWrittenOnlyOnce() {
        updatedFromAnOlderVersion()
        saved.edit().putInt("lock_mode", Prefs.LOCK_ALWAYS).putBoolean("currency_rates", true).commit()
        Prefs.init(context)
        assertEquals(Prefs.LOCK_ALWAYS, Prefs.lockMode)
        assertTrue(Prefs.currencyRates)

        Prefs.lockMode = Prefs.LOCK_OFF
        saved.edit().remove("currency_rates").commit()
        Prefs.init(context)
        assertEquals(Prefs.LOCK_OFF, Prefs.lockMode)
        assertTrue("Once settled, a missing value takes the new default", Prefs.currencyRates)
    }
}
