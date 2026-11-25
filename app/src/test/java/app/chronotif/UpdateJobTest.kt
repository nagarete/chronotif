// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The daily update check waits for an unmetered network and a battery that isn't low. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateJobTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val js get() = context.getSystemService(JobScheduler::class.java)

    @Before
    fun setUp() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        js.cancelAll()
    }

    @Suppress("DEPRECATION")
    private fun assertWaitsForWifiAndBattery(job: JobInfo?) {
        requireNotNull(job)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)
        assertTrue(job.isRequireBatteryNotLow)
    }

    @Test
    fun turningAutoUpdateOnSchedulesTheCheckForWifiAndBattery() {
        Prefs.autoUpdate = true
        Updater.sync(context)
        assertWaitsForWifiAndBattery(js.getPendingJob(1))

        Prefs.autoUpdate = false
        Updater.sync(context)
        assertNull(js.getPendingJob(1))
    }

    @Test
    fun aJobScheduledByAnOlderVersionIsReplaced() {
        js.schedule(
            JobInfo.Builder(1, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(86_400_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build(),
        )
        Prefs.autoUpdate = true
        Updater.sync(context)
        assertWaitsForWifiAndBattery(js.getPendingJob(1))
    }
}
