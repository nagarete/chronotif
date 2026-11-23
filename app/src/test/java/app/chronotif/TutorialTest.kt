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
import app.chronotif.core.AppTier
import app.chronotif.core.Attention
import app.chronotif.core.Tier
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
        // Other test classes run activities that mark screens as shown, such as Search.
        end()
    }

    @After
    fun end() {
        // Tutorial is one object for the app: leave no screen marked as shown for the next test.
        Tutorial.Surface.entries.forEach { Tutorial.shown(it, false) }
        Tutorial.pause()
    }

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
        Tutorial.shown(Tutorial.Surface.SCREEN, true)
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

        // Priority apps: a fresh Parcelly card arrives in New, and making Parcelly one lifts it above New.
        Tutorial.next(context)
        assertFalse(r.tried)
        val parcel = r.source.visible().filter { it.pkg == Samples.parcelly.pkg }.maxBy { it.alertTime }
        assertTrue("The fresh card is New", parcel.alertTime > r.source.lastCheck)
        r.source.setTier(Samples.parcelly.pkg, Tier.PRIORITY)
        assertTrue(r.tried)
        val lifted = Attention.sections(r.source.visible(), r.source.lastCheck, r.source::tier, r.source::openedAt)
        assertEquals(listOf(parcel.key), lifted.priority.map { it.key })
        assertEquals("Nothing reached the real settings", AppTier.NORMAL, Prefs.appTier(Samples.parcelly.pkg))

        // Quiet apps: a fresh Daybook card arrives, and making Daybook one sends it to Earlier.
        Tutorial.next(context)
        val day = r.source.visible().filter { it.pkg == Samples.daybook.pkg }.maxBy { it.alertTime }
        r.source.setTier(Samples.daybook.pkg, Tier.QUIET)
        assertTrue(r.tried)
        val quiet = Attention.sections(r.source.visible(), r.source.lastCheck, r.source::tier, r.source::openedAt)
        assertTrue(quiet.earlier.any { it.key == day.key })

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
        Tutorial.shown(Tutorial.Surface.SCREEN, true)
        r.source.noticed(Noticed.PANEL_OPENED)
        Tutorial.shown(Tutorial.Surface.SCREEN, false)
        assertSame("The Panel is still open over the screen that left", r, Tutorial.run)
        r.source.noticed(Noticed.PANEL_CLOSED)
        assertNull(Tutorial.run)
        assertNull(Tutorial.source)
    }

    @Test
    fun theSearchPartFollowsSearchAndHoldsItsLaunches() {
        assertFalse("Outside the Search part, Search opens things", Tutorial.holdsLaunch(context, "Calculator"))
        val r = Tutorial.start(context, Tutorial.Part.SEARCH)
        Tutorial.shown(Tutorial.Surface.SCREEN, true)
        r.source.noticed(Noticed.SEARCH)
        assertTrue("Pulled down in the Panel", r.tried)
        Tutorial.next(context)
        Tutorial.notice(TutorialSource.Event.SEARCH_ELSEWHERE)
        assertTrue("Opened from the edge handle", r.tried)
        Tutorial.next(context)
        Tutorial.notice(TutorialSource.Event.ANSWER_SHOWN)
        assertTrue(r.tried)
        Tutorial.next(context)
        assertTrue("A tapped app doesn't open", Tutorial.holdsLaunch(context, "Calculator"))
        assertTrue(r.tried)
        assertNull(shadowOf(context as Application).nextStartedActivity)

        Tutorial.next(context)
        Tutorial.recheck(context)
        assertFalse("No widget yet", r.tried)
        shadowOf(android.appwidget.AppWidgetManager.getInstance(context)).createWidget(SearchWidget::class.java, R.layout.search_widget)
        Tutorial.recheck(context)
        assertTrue("The widget is on the home screen", r.tried)
    }

    @Test
    fun searchFromTheEdgeHandleKeepsTheRunGoing() {
        val r = Tutorial.start(context, Tutorial.Part.SEARCH)
        Tutorial.shown(Tutorial.Surface.SCREEN, true)
        Tutorial.shown(Tutorial.Surface.SEARCH, true)
        Tutorial.shown(Tutorial.Surface.SCREEN, false)
        assertSame("Search covers the tutorial screen", r, Tutorial.run)
        Tutorial.shown(Tutorial.Surface.SEARCH, false)
        assertNull(Tutorial.run)
    }

    @Test
    fun finishingAPartMovesOnToTheNextWithTheSameSamples() {
        val r = Tutorial.start(context, part)
        repeat(Tutorial.steps(part).size) { Tutorial.next(context) }
        assertTrue(r.finished)
        assertEquals(Tutorial.Part.SEARCH, Tutorial.nextPart(part))
        val search = Tutorial.start(context, Tutorial.Part.SEARCH)
        assertSame("The Panel still open keeps its samples", r.source, search.source)
        r.source.noticed(Noticed.SEARCH)
        assertTrue("and they report to the new part", search.tried)
    }

    private fun tapAt(a: android.app.Activity, x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        a.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        idle(40)
        a.dispatchTouchEvent(android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    @Test
    fun theLockScreenStandInRunsUnlockedOnTheSamplesAndUnlocksNothing() {
        postReal("Real secret")
        val r = Tutorial.start(context, Tutorial.Part.LOCK_SCREEN)
        val controller = Robolectric.buildActivity(app.chronotif.ui.LockActivity::class.java,
            app.chronotif.ui.LockActivity.practice(context)).setup()
        idle(1_500)
        val a = controller.get()
        assertFalse("On an unlocked phone the stand-in stays up", a.isFinishing)
        val shown = texts(a.window.decorView)
        assertFalse(shown.contains("Real secret"))
        assertTrue("with the step's card", shown.contains(context.getString(R.string.tutorial_pile_title)))

        val scroll = views(a.window.decorView).filterIsInstance<android.widget.ScrollView>().first { it.isFillViewport }
        scroll.scrollTo(0, 10_000)
        idle()
        a.back()
        assertTrue("Back folded the Pile", r.tried)
        assertFalse(a.isFinishing)
        idle(1_000) // Let the fold finish: a touch during a scroll only stops it.

        repeat(3) { Tutorial.next(context) }
        views(a.window.decorView).filterIsInstance<android.widget.TextClock>().maxBy { it.textSize }.performClick()
        assertTrue("The clock says it opens after an unlock", r.tried)
        assertNull("and asks for none", ReflectionHelpers.getStaticField<Any?>(
            org.robolectric.shadows.ShadowKeyguardManager::class.java, "callback"))

        repeat(2) { Tutorial.next(context) }
        val spot = 4 * context.resources.displayMetrics.density
        tapAt(a, spot, spot)
        idle(100)
        tapAt(a, spot, spot)
        idle(2_000)
        assertTrue("A double tap goes black for a moment", r.tried)
        assertEquals("then comes back, without touching the screen timeout", -1L,
            android.view.WindowManager.LayoutParams::class.java.getField("userActivityTimeout").getLong(a.window.attributes))

        Tutorial.next(context)
        val flashlight = views(a.window.decorView).filterIsInstance<android.widget.ImageView>()
            .first { it.contentDescription == context.getString(R.string.lock_shortcut_flashlight) }
        flashlight.performLongClick()
        assertTrue("Holding a shortcut counts", r.tried)
        assertEquals("and says what it would do", context.getString(R.string.tutorial_shortcut_flashlight),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())

        Tutorial.next(context)
        Tutorial.steps(Tutorial.Part.LOCK_SCREEN).last().extras.first { it.label == R.string.lock_new }.act(context)
        assertTrue(r.tried)
        assertEquals(Prefs.LOCK_WHEN_NEW, Prefs.lockMode)

        controller.pause().stop().destroy()
        LockSession.unlocked()
        assertEquals("No real check from the stand-in", 0L, Prefs.lastCheck)
    }
}
