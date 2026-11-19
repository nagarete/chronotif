// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.chronotif.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PullToSearchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private class Run(val opened: Int, val cancelled: Int, val consumed: Boolean)

    /** One straight drag of [dyDp] down (negative: up) from the top of a list. */
    private fun drag(dyDp: Float, atTop: Boolean = true, ready: Boolean = true, dxDp: Float = 0f): Run {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val list = View(activity)
        var opened = 0
        var cancelled = 0
        val pull = PullToSearch(list, atTop = { atTop }, ready = { ready }) { opened++ }
        val d = activity.resources.displayMetrics.density
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, y: Float) = MotionEvent.obtain(t, t, action, x, y, 0)
        var consumed = pull.onTouch(ev(MotionEvent.ACTION_DOWN, 100f, 100f)) { cancelled++ }
        for (step in 1..10) {
            consumed = pull.onTouch(ev(MotionEvent.ACTION_MOVE, 100f + dxDp * d * step / 10, 100f + dyDp * d * step / 10)) { cancelled++ } || consumed
        }
        consumed = pull.onTouch(ev(MotionEvent.ACTION_UP, 100f + dxDp * d, 100f + dyDp * d)) { cancelled++ } || consumed
        return Run(opened, cancelled, consumed)
    }

    @Test fun aPullPastTheThresholdOpensSearchAndCancelsTheList() {
        val run = drag(120f)
        assertEquals(1, run.opened)
        assertEquals("Cards stop tracking the gesture once, when it becomes a pull", 1, run.cancelled)
        assertTrue(run.consumed)
    }

    @Test fun aShortPullSpringsBack() {
        val run = drag(40f)
        assertEquals(0, run.opened)
        assertTrue("It was still a pull, so the list doesn't also get it", run.consumed)
    }

    @Test fun nothingOpensAwayFromTheTopBeforeTheOpenAnimationOrWhenSwitchedOff() {
        assertEquals("A scrolled list scrolls instead", 0, drag(120f, atTop = false).opened)
        assertEquals("The panel's opening swipe doesn't run on into search", 0, drag(120f, ready = false).opened)
        assertEquals("Swiping up isn't a pull", 0, drag(-120f).opened)
        assertEquals("A sideways swipe (a card) isn't a pull", 0, drag(30f, dxDp = 120f).opened)
        Prefs.appSearch = false
        val off = drag(120f)
        assertEquals(0, off.opened)
        assertFalse(off.consumed)
    }
}
