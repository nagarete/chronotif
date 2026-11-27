// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.content.Context
import app.chronotif.core.Usage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.Executor

/** Usage data keeps a batch until PostHog has it, and counts in memory until they're written (ADR 0016). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UsageDataTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val saved get() = context.getSharedPreferences("chronotif", Context.MODE_PRIVATE)
    private val sent = ArrayList<String>()
    private var answer = 200

    @Before
    fun setUp() {
        saved.edit().clear().commit()
        Prefs.init(context)
        UsageData.key = "test"
        UsageData.worker = Executor { it.run() }
        UsageData.transport = { sent += it; answer }
        UsageData.failedAt = 0L
        // A new install sends "usage_data_on" at once.
        UsageData.init(context)
        assertEquals(1, sent.size)
        assertTrue(sent[0].contains("\"usage_data_on\""))
        assertNull(Prefs.usageBatch)
    }

    @After
    fun tearDown() {
        UsageData.key = BuildConfig.USAGE_DATA_KEY
    }

    private fun dayEnds() {
        saved.edit().putLong("usage_day", LocalDate.now().toEpochDay() - 1).commit()
    }

    private fun count(n: Int) = repeat(n) { UsageData.count(Usage.Count.PANEL_OPENED) }

    /** An hour on, when a failed send may be tried again. */
    private fun later() {
        UsageData.failedAt = 0L
    }

    @Test
    fun aRetrySendsTheSameBatchAndCountsMadeSinceWaitForTheNext() {
        count(3)
        dayEnds()
        answer = 503
        UsageData.sendIfDue()
        assertEquals(2, sent.size)
        assertTrue(sent[1].contains("\"n_panel_opened\":3"))

        count(2)
        later()
        answer = 200
        UsageData.sendIfDue()
        assertEquals(3, sent.size)
        // The same body, so the same event IDs: a batch whose answer was lost is recorded once.
        assertEquals(sent[1], sent[2])
        assertNull(Prefs.usageBatch)

        UsageData.flush()
        assertEquals("panel_opened=2", Prefs.usageCounts)
        assertEquals(LocalDate.now().toEpochDay(), Prefs.usageDay)
    }

    @Test
    fun aBatchIsKeptOnDiskBeforeItIsSent() {
        count(1)
        dayEnds()
        UsageData.transport = { body ->
            assertEquals(body, saved.getString("usage_batch", null))
            // What went into the batch is no longer waiting.
            assertEquals("", saved.getString("usage_counts", null))
            sent += body
            200
        }
        UsageData.sendIfDue()
        assertEquals(2, sent.size)
        assertNull(Prefs.usageBatch)
    }

    @Test
    fun countsWaitInMemoryUntilWritten() {
        count(2)
        assertEquals(emptyMap<String, Int>(), Usage.decodeCounts(Prefs.usageCounts))
        UsageData.flush()
        assertEquals("panel_opened=2", Prefs.usageCounts)
    }

    @Test
    fun aBatchPostHogRefusesIsDropped() {
        dayEnds()
        answer = 400
        UsageData.sendIfDue()
        assertEquals(2, sent.size)
        assertNull(Prefs.usageBatch)
    }

    @Test
    fun turningOffForgetsAWaitingBatchAndSendsOnlyTheLastEvent() {
        count(1)
        dayEnds()
        answer = 503
        UsageData.sendIfDue()
        assertTrue(Prefs.usageBatch != null)

        answer = 200
        UsageData.set(false)
        assertEquals(3, sent.size)
        assertTrue(sent[2].contains("\"usage_data_off\""))
        assertNull(Prefs.usageBatch)
        assertNull(Prefs.usageCounts)

        later()
        UsageData.sendIfDue()
        UsageData.count(Usage.Count.PANEL_OPENED)
        UsageData.flush()
        assertEquals(3, sent.size)
        assertNull(Prefs.usageCounts)
    }

    @Test
    fun theDailyBatchSaysHowTheLastUpdateEnded() {
        saved.edit().putLong("update_daily_at", System.currentTimeMillis() - 3 * 86_400_000L).commit()
        Updater.outcome(Updater.Outcome.HTTP, 404, manual = false)
        dayEnds()
        UsageData.sendIfDue()
        assertEquals(2, sent.size)
        assertTrue(sent[1].contains("\"update_daily_days\":3"))
        assertTrue(sent[1].contains("\"update_outcome\":\"http\""))
        assertTrue(sent[1].contains("\"update_outcome_code\":404"))
        assertTrue(sent[1].contains("\"update_outcome_manual\":false"))
        assertTrue(sent[1].contains("\"perm_notifications\":"))
        assertTrue(sent[1].contains("\"perm_install\":"))
    }
}
