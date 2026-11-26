// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.chronotif.ListenerService
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.SupportAsk
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowSettings

/** The Support sheet: over Settings now and then, leading to the Support section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SupportSheetTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sp get() = context.getSharedPreferences("chronotif", Context.MODE_PRIVATE)

    @Before fun setup() {
        sp.edit().clear().commit()
        Prefs.init(context)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, ListenerService::class.java).flattenToString())
        ShadowSettings.setCanDrawOverlays(true)
    }

    /** Pretends this install first ran a version with Support [ago] milliseconds back. */
    private fun firstRan(ago: Long) {
        sp.edit().putLong("support_since", System.currentTimeMillis() - ago).commit()
    }

    private fun open(state: Bundle? = null) =
        Robolectric.buildActivity(SetupActivity::class.java).create(state).start().resume().visible().get()
            .also { shadowOf(Looper.getMainLooper()).idle() }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun SetupActivity.shown(text: Int) =
        all(window.decorView).any { it is TextView && it.isShown && it.text == getString(text) }

    private fun SetupActivity.tap(text: Int) {
        all(window.decorView).first { it is TextView && it.isShown && it.text == getString(text) }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `no sheet in the first week`() {
        firstRan(SupportAsk.WAIT - 60_000)
        assertFalse(open().shown(R.string.support_ask_title))
    }

    @Test fun `after a week Settings asks once, and Not now closes it for another week`() {
        firstRan(SupportAsk.WAIT + 60_000)
        val activity = open()
        assertTrue(activity.shown(R.string.support_ask_title))
        activity.tap(R.string.support_ask_later)
        assertFalse(activity.shown(R.string.support_ask_title))
        assertFalse(Prefs.supportTapped)
        assertFalse(open().shown(R.string.support_ask_title))
    }

    @Test fun `Support opens the Support section and counts as tapped`() {
        firstRan(SupportAsk.WAIT + 60_000)
        val activity = open()
        activity.tap(R.string.support_ask_go)
        assertTrue(Prefs.supportTapped)
        assertEquals("SUPPORT", Prefs.settingsSection)
        assertTrue(activity.shown(R.string.support_kofi))
    }

    @Test fun `Back closes the sheet without counting toward leaving`() {
        firstRan(SupportAsk.WAIT + 60_000)
        val activity = open()
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.shown(R.string.support_ask_title))
        assertFalse(activity.isFinishing)
    }

    @Test fun `not while setup is unfinished`() {
        firstRan(SupportAsk.WAIT + 60_000)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        assertFalse(open().shown(R.string.support_ask_title))
    }

    @Test fun `not on a recreate`() {
        firstRan(SupportAsk.WAIT + 60_000)
        assertFalse(open(Bundle()).shown(R.string.support_ask_title))
    }
}
