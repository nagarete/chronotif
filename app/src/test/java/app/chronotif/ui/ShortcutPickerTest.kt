// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.core.LockShortcut
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
import org.robolectric.shadows.ShadowDialog

/** Settings → Lock screen → Shortcuts: a corner's sheet of actions and apps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShortcutPickerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun clickText(root: View, s: String) {
        var v: View = all(root).filterIsInstance<TextView>().last { it.text.toString() == s }
        while (!v.isClickable) v = v.parent as View
        v.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun openSheet(slot: Int): android.app.Dialog {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        clickText(activity.window.decorView, activity.getString(R.string.setup_lock))
        clickText(activity.window.decorView, activity.getString(slot))
        return ShadowDialog.getLatestDialog()
    }

    @Test fun aCornerOffersTheActionsWithIconsAndApps() {
        val sheet = openSheet(R.string.lock_shortcut_left).window!!.decorView
        val texts = all(sheet).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
        assertTrue(texts.contains(context.getString(R.string.lock_shortcut_flashlight)))
        assertTrue(texts.contains(context.getString(R.string.lock_shortcut_dnd)))
        assertFalse("Camera is in the other corner", texts.contains(context.getString(R.string.lock_shortcut_camera)))
        assertTrue(texts.contains(context.getString(R.string.lock_shortcut_apps)))
        assertTrue(all(sheet).any { it is android.widget.EditText })
    }

    @Test fun pickingATileSetsTheCornerAndCloses() {
        val dialog = openSheet(R.string.lock_shortcut_left)
        clickText(dialog.window!!.decorView, context.getString(R.string.lock_shortcut_dnd))
        assertEquals(LockShortcut.DO_NOT_DISTURB, Prefs.lockShortcutLeft)
        assertFalse(dialog.isShowing)
    }
}
