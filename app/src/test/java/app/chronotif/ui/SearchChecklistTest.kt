// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.chronotif.Prefs
import app.chronotif.R
import app.chronotif.SearchSetup
import app.chronotif.SearchSetup.Step
import app.chronotif.SearchWidget
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

/** Settings → Search opens with a checklist like General's, and each step can be put off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchChecklistTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun shown(v: View) = all(v).filterIsInstance<TextView>().filter { it.isShown }

    private fun texts(v: View) = shown(v).map { it.text.toString() }

    private fun SetupActivity.root() = window.decorView

    /** Settings, moved to the Search page from the drawer. */
    private fun openSearch(): SetupActivity {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val name = activity.getString(R.string.setup_search)
        all(activity.root()).filterIsInstance<TextView>()
            .first { it.text.toString() == name && (it.parent as View).isClickable }
            .let { (it.parent as View).performClick() }
        return activity
    }

    private fun SetupActivity.tap(label: String) =
        shown(root()).first { it.text.toString() == label && it.isClickable }.performClick()

    private fun SetupActivity.grantLast(permission: String) {
        val asked = shadowOf(this).lastRequestedPermission
        assertTrue(asked.requestedPermissions.contains(permission))
        shadowOf(application).grantPermissions(permission)
        onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
    }

    @Test fun `a new install has five steps left, and the tile never counts`() {
        assertTrue("Currency rates are on for new installs", SearchSetup.done(context, Step.CURRENCY))
        assertEquals(5, SearchSetup.left(context))
        assertEquals(Step.CONTACTS, SearchSetup.next(context))

        SearchSetup.skip(Step.CONTACTS)
        assertEquals(4, SearchSetup.left(context))
        assertEquals(Step.CALLS, SearchSetup.next(context))

        Prefs.searchCalendar = true
        assertFalse("A switch on without its permission isn't done", SearchSetup.done(context, Step.CALENDAR))
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        assertTrue(SearchSetup.done(context, Step.CALENDAR))

        shadowOf(AppWidgetManager.getInstance(context)).createWidget(SearchWidget::class.java, R.layout.search_widget)
        assertTrue(SearchSetup.done(context, Step.WIDGET))
        SearchSetup.skip(Step.FILES)
        assertEquals("Only Call from Search is left", 1, SearchSetup.left(context))
        assertFalse(SearchSetup.handled(context, Step.TILE))

        SearchSetup.skip(Step.CALLS)
        assertEquals(0, SearchSetup.left(context))
        assertNull(SearchSetup.next(context))
    }

    @Test fun `the Search page opens with the checklist, and Not now puts a step off`() {
        val a = openSearch()
        val left = { n: Int -> a.resources.getQuantityString(R.plurals.setup_steps_left, n, n) }
        assertTrue(texts(a.root()).contains(left(5)))
        assertTrue(texts(a.root()).contains(a.getString(R.string.search_setup_next, a.getString(R.string.search_find_contacts))))

        a.tap(a.getString(R.string.not_now))
        assertTrue(texts(a.root()).contains(left(4)))
        assertTrue(texts(a.root()).contains(a.getString(R.string.search_setup_next, a.getString(R.string.search_calls))))
        assertTrue(texts(a.root()).contains(a.getString(R.string.search_setup_skipped)))
    }

    @Test fun `Allow asks for the permission and turns the switch below on too`() {
        val a = openSearch()
        a.tap(a.getString(R.string.allow))
        a.grantLast(Manifest.permission.READ_CONTACTS)
        assertTrue(Prefs.searchContacts)
        val left = a.resources.getQuantityString(R.plurals.setup_steps_left, 4, 4)
        assertTrue(texts(a.root()).contains(left))
        assertTrue(texts(a.root()).contains(a.getString(R.string.search_setup_on)))
        // The switch below follows: its row is the one whose title isn't inside the checklist.
        val switches = all(a.root()).filterIsInstance<M3Switch>()
        assertTrue("The Find contacts switch is on", switches.any { it.isChecked && rowTitle(it) == a.getString(R.string.search_find_contacts) })
    }

    @Test fun `a switch below still asks for its permission`() {
        val a = openSearch()
        val sw = all(a.root()).filterIsInstance<M3Switch>().first { rowTitle(it) == a.getString(R.string.search_calls) }
        generateSequence(sw.parent as View) { it.parent as? View }.first { it.isClickable }.performClick()
        a.grantLast(Manifest.permission.CALL_PHONE)
        assertTrue(Prefs.searchCalls)
        assertTrue(sw.isChecked)
        assertTrue(SearchSetup.done(a, Step.CALLS))
    }

    @Test fun `Find files opens Android's All files access page, and isn't done until it's allowed`() {
        val a = openSearch()
        assertTrue("This build finds documents", app.chronotif.Files.allFilesBuild(a))
        val sw = all(a.root()).filterIsInstance<M3Switch>().first { rowTitle(it) == a.getString(R.string.search_find_files) }
        generateSequence(sw.parent as View) { it.parent as? View }.first { it.isClickable }.performClick()
        val opened = shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, opened.action)
        assertTrue(Prefs.searchFiles)
        assertFalse("Not done until Android allows it", SearchSetup.done(a, Step.FILES))
    }

    @Test fun `with the old opt-in, Turn on switches currency rates on`() {
        Prefs.currencyRates = false
        val a = openSearch()
        a.tap(a.getString(R.string.turn_on))
        assertTrue(Prefs.currencyRates)
        val sw = all(a.root()).filterIsInstance<M3Switch>().first { rowTitle(it) == a.getString(R.string.search_currency_rates) }
        assertTrue(sw.isChecked)
    }

    @Test fun `a home screen that can't place widgets gets directions instead of a button`() {
        shadowOf(AppWidgetManager.getInstance(context)).setRequestPinAppWidgetSupported(false)
        val a = openSearch()
        assertTrue(texts(a.root()).contains(a.getString(R.string.search_setup_widget_manual)))
        shadowOf(AppWidgetManager.getInstance(context)).setRequestPinAppWidgetSupported(true)
        val b = openSearch()
        assertFalse(texts(b.root()).contains(b.getString(R.string.search_setup_widget_manual)))
    }

    /** The title of the settings row a switch sits in. */
    private fun rowTitle(sw: M3Switch): String? =
        all(sw.parent as View).filterIsInstance<TextView>().firstOrNull()?.text?.toString()
}
