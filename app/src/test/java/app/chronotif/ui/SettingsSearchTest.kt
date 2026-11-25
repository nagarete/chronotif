// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.chronotif.Prefs
import app.chronotif.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsSearchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun texts(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private class Opened(val activity: SetupActivity, val root: View, val field: EditText)

    private fun open(): Opened {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val root = activity.window.decorView
        val hint = activity.getString(R.string.settings_search_hint)
        all(root).first { it.contentDescription == hint && it.isClickable }.performClick()
        val field = all(root).filterIsInstance<EditText>().single()
        return Opened(activity, root, field)
    }

    @Test fun `results follow each letter typed`() {
        val o = open()
        o.field.setText("colo")
        val shown = texts(o.root)
        assertTrue(shown.contains(o.activity.getString(R.string.handle_colour)))
        assertTrue(shown.contains(o.activity.getString(R.string.handle_new_colour)))
        o.field.setText("colour when")
        assertTrue(texts(o.root).contains(o.activity.getString(R.string.handle_new_colour)))
        assertTrue(!texts(o.root).contains(o.activity.getString(R.string.handle_colour)))
    }

    @Test fun `a result shows where it lives and its value`() {
        val o = open()
        o.field.setText("hide in landscape")
        val shown = texts(o.root)
        assertTrue(shown.contains("Edge handle › Placement"))
        val value = if (Prefs.handleHideLandscape) R.string.value_on else R.string.value_off
        assertTrue(shown.contains(o.activity.getString(value)))
    }

    @Test fun `words people type find settings their names don't say`() {
        val o = open()
        o.field.setText("color")
        assertTrue(texts(o.root).contains(o.activity.getString(R.string.handle_colour)))
        o.field.setText("dismiss")
        assertTrue(texts(o.root).contains(o.activity.getString(R.string.swipe_to_clear)))
    }

    @Test fun `picking a result opens its page and closes search`() {
        val o = open()
        o.field.setText("clock size")
        val label = o.activity.getString(R.string.header_clock_size, Prefs.headerClockSize)
        val row = all(o.root).first { it.getTag(R.id.search_entry) != null && texts(it).isNotEmpty() }
        row.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val shown = texts(o.root)
        assertTrue(shown.contains(o.activity.getString(R.string.setup_panel)))
        assertTrue(shown.contains(label))
        assertTrue(!o.field.isShown)
        assertEquals("PANEL", Prefs.settingsSection)
    }

    /** What a page row is set to, read from its views: a switch's On or Off, or the picked option's name. */
    private fun shownValue(activity: SetupActivity, row: View): String? {
        val views = all(row)
        views.filterIsInstance<M3Switch>().firstOrNull()?.let {
            return activity.getString(if (it.isChecked) R.string.value_on else R.string.value_off)
        }
        val picked = views.firstOrNull { it !== row && it.isSelected } ?: return null
        return when (picked) {
            is TextView -> picked.text.toString()
            is android.widget.LinearLayout -> (picked.getChildAt(picked.childCount - 1) as? TextView)?.text?.toString()
            else -> picked.contentDescription?.toString()
        }
    }

    /**
     * Opens every page from the drawer and holds search's index to it: each titled row, in page
     * order, with the value the row shows. Search's checklist steps for contacts, calls, currency
     * and calendar aren't in it, since their switches further down are.
     */
    private fun indexMatchesThePages() {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val root = activity.window.decorView
        val index = activity.searchIndex()
        val sections = index.map { it.first }.distinct()
        fun name(id: Int?) = id?.let { activity.resources.getResourceEntryName(it) }
        val drawerHeight = (52 * activity.resources.displayMetrics.density).toInt()
        fun drawerRows() = all(root).filter {
            it is android.widget.LinearLayout && it.minimumHeight == drawerHeight && it.hasOnClickListeners() &&
                it.getChildAt(0) is android.widget.ImageView
        }
        assertEquals("Every section has something to find", drawerRows().size, sections.size)
        sections.forEachIndexed { i, section ->
            drawerRows()[i].performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val rows = all(root).filter { it.tag is Int }.toMutableList()
            if (section == "SEARCH") {
                for (step in listOf(R.string.search_find_contacts, R.string.search_calls, R.string.search_currency_rates, R.string.search_find_calendar)) {
                    rows.remove(rows.first { it.tag == step })
                }
            }
            val entries = index.filter { it.first == section && it.second != null }
            assertEquals("$section: the rows search finds", rows.map { name(it.tag as Int) }, entries.map { name(it.second) })
            assertEquals("$section: the values search shows", rows.map { shownValue(activity, it) }, entries.map { it.third })
        }
    }

    @Test fun `the index holds every row the pages show, with its value`() {
        indexMatchesThePages()
    }

    @Test fun `rows a page shows only sometimes are found only while it does`() {
        Prefs.usageNoticed = true
        Prefs.handleShape = Prefs.SHAPE_CIRCLE
        Prefs.lockMode = Prefs.LOCK_ALWAYS
        Prefs.headerDateBeside = true
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        indexMatchesThePages()
    }

    @Test fun `nothing found says so`() {
        val o = open()
        o.field.setText("zzzz")
        assertTrue(texts(o.root).contains(o.activity.getString(R.string.settings_search_none, "zzzz")))
    }
}
