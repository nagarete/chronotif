// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The daily update check runs on any network, whether or not Auto-update is on (ADR 0025). */
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
    private fun assertChecksOnAnyNetwork(job: JobInfo?) {
        requireNotNull(job)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertFalse(job.isRequireBatteryNotLow)
    }

    @Test
    fun theDailyCheckIsScheduledWithAutoUpdateOnOrOff() {
        Updater.sync(context)
        assertChecksOnAnyNetwork(js.getPendingJob(1))

        Prefs.autoUpdate = false
        js.cancelAll()
        Updater.sync(context)
        assertChecksOnAnyNetwork(js.getPendingJob(1))
    }

    @Test
    fun aJobScheduledByAnOlderVersionIsReplaced() {
        js.schedule(
            JobInfo.Builder(1, ComponentName(context, UpdateJob::class.java))
                .setPeriodic(86_400_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
        Updater.sync(context)
        assertChecksOnAnyNetwork(js.getPendingJob(1))
    }

    @Test
    fun turningAutoUpdateOffDropsADownloadWaitingForWifi() {
        js.schedule(
            JobInfo.Builder(2, ComponentName(context, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build(),
        )
        Prefs.autoUpdate = false
        Updater.sync(context)
        assertEquals(null, js.getPendingJob(2))
    }
}
