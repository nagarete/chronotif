// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import app.chronotif.Prefs
import app.chronotif.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** Leaving settings takes two Backs, so one stray edge swipe doesn't drop the user out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsBackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        ShadowToast.reset()
    }

    private fun open() = Robolectric.buildActivity(SetupActivity::class.java).setup().get()

    private fun SetupActivity.back() {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val again get() = context.getString(R.string.settings_back_again)

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Test fun `the first Back says so and stays, the second leaves`() {
        val activity = open()
        activity.back()
        assertFalse(activity.isFinishing)
        assertEquals(again, ShadowToast.getTextOfLatestToast())
        activity.back()
        assertTrue(activity.isFinishing)
    }

    @Test fun `a second Back after the toast has gone only says so again`() {
        val activity = open()
        activity.back()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
        ShadowToast.reset()
        activity.back()
        assertFalse(activity.isFinishing)
        assertEquals(again, ShadowToast.getTextOfLatestToast())
    }

    @Test fun `Back closes search before it counts toward leaving`() {
        val activity = open()
        val hint = activity.getString(R.string.settings_search_hint)
        all(activity.window.decorView).first { it.contentDescription == hint && it.isClickable }.performClick()
        activity.back()
        assertFalse(activity.isFinishing)
        assertNull(ShadowToast.getTextOfLatestToast())
        activity.back()
        assertFalse(activity.isFinishing)
        activity.back()
        assertTrue(activity.isFinishing)
    }
}
