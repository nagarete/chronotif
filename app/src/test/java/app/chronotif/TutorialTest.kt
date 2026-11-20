// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.chronotif.CardSource.Noticed
import app.chronotif.ui.PanelActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The Tutorial runs the real Panel on made-up samples: the phone's notifications never show,
 * and neither the real last check nor Android is touched. Steps notice what the user tried,
 * the place is kept, and the run ends with the screen and the Panel gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TutorialTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val part = Tutorial.Part.NOTIFICATIONS

    @Before
    fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        NotifStore.connect(emptyArray(), noRanking())
    }

    @After
    fun end() = Tutorial.pause()

    private fun noRanking(): RankingMap = ReflectionHelpers.callConstructor(RankingMap::class.java,
        ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))

    @Suppress("DEPRECATION")
    private fun postReal(title: String) = NotifStore.post(StatusBarNotification("example.bank", "example.bank", 7, null,
        Process.myUid(), 0, 0, Notification.Builder(context, "x").setContentTitle(title).build(), Process.myUserHandle(),
        System.currentTimeMillis()), noRanking())

    private fun views(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun texts(v: View) = views(v).filterIsInstance<TextView>().map { it.text.toString() }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun madeUpAppsHaveTheirOwnNamesAndIcons() {
        assertEquals("Murmur", Apps.label(context, Samples.murmur.pkg).toString())
        val e = Samples.entry(context, Samples.parcelly, 1, 1_000) { setContentTitle("x") }
        assertTrue(e.isSample)
        assertNotNull(Apps.icon(context, e))
    }

    @Test
    fun thePanelShowsOnlyTheSamplesAndChecksOnlyThem() {
        postReal("Real secret")
        Tutorial.start(context, part)
        val source = Tutorial.run!!.source
        val before = source.lastCheck
        val controller = Robolectric.buildActivity(PanelActivity::class.java).setup()
        idle(2_000)
        val shown = texts(controller.get().window.decorView)
        assertFalse("The phone's own notifications are hidden", shown.contains("Real secret"))
        assertTrue(shown.contains(context.getString(R.string.sample_parcelly_title)))
        assertTrue("The guide card shows the step", shown.contains(context.getString(R.string.tutorial_open_title)))
        controller.pause().stop().destroy()
        assertTrue("The samples' own check moved", source.lastCheck > before)
        assertEquals("the real one didn't", 0L, Prefs.lastCheck)
        assertEquals(1, NotifStore.newCount())
    }

    @Test
    fun stepsNoticeWhatWasTriedAndThePlaceIsKept() {
        val r = Tutorial.start(context, part)
        Tutorial.screenShown = true
        assertEquals(0, r.step)
        r.source.noticed(Noticed.PANEL_OPENED)
        assertTrue("Opening the Panel is step 1", r.tried)

        Tutorial.next(context)
        assertEquals("NOTIFICATIONS:1", Prefs.tutorialPlace)
        assertFalse(r.tried)
        idle(3_000)
        val maya = r.source.visible().first { it.pkg == Samples.murmur.pkg }
        assertEquals("Maya wrote again", 3, Content.incomingIds(maya.n).size)
        r.source.noticed(Noticed.PANEL_OPENED)
        assertFalse("Reopening without a check isn't it", r.tried)
        r.source.markChecked(maya.alertTime)
        r.source.noticed(Noticed.PANEL_OPENED)
        assertTrue(r.tried)

        Tutorial.next(context)
        r.source.noticed(Noticed.STACK_CLOSED)
        assertFalse(r.tried)
        r.source.noticed(Noticed.STACK_OPENED)
        r.source.noticed(Noticed.STACK_CLOSED)
        assertTrue(r.tried)

        Tutorial.back(context)
        assertEquals(1, r.step)
        Tutorial.next(context)
        Tutorial.next(context)
        r.source.snooze(r.source.visible().first(), Long.MAX_VALUE)
        assertTrue("Snoozing from the Preview", r.tried)

        Tutorial.next(context)
        r.source.dismissAll(listOf(r.source.visible().first()))
        assertTrue(r.tried)

        Tutorial.next(context)
        Tutorial.next(context)
        assertTrue(r.finished)
        assertTrue(Tutorial.finished(part))
        assertNull(Tutorial.placeOf(part))
        assertEquals("Nothing touched the real check", 0L, Prefs.lastCheck)
    }

    @Test
    fun aPartContinuesWhereItWasLeft() {
        Tutorial.start(context, part)
        Tutorial.next(context)
        Tutorial.next(context)
        Tutorial.pause()
        assertNull(Tutorial.source)
        assertEquals(2, Tutorial.placeOf(part))
        assertEquals(2, Tutorial.start(context, part).step)
        Tutorial.pause()
        assertEquals("Start again begins at step one", 0, Tutorial.start(context, part, fromStart = true).step)
    }

    @Test
    fun theRunEndsOnceTheScreenAndThePanelAreBothGone() {
        val r = Tutorial.start(context, part)
        Tutorial.screenShown = true
        r.source.noticed(Noticed.PANEL_OPENED)
        Tutorial.screenShown = false
        assertSame("The Panel is still open over the screen that left", r, Tutorial.run)
        r.source.noticed(Noticed.PANEL_CLOSED)
        assertNull(Tutorial.run)
        assertNull(Tutorial.source)
    }
}
