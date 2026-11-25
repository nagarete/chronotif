// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import app.chronotif.Prefs
import org.junit.Assert.assertEquals
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Prefs.init(context)
    }

    // A destroyed controller never stops its activity, so Search stays marked as shown in the
    // Tutorial, which is one object for the app.
    @org.junit.After fun forgetSearch() {
        app.chronotif.Tutorial.Surface.entries.forEach { app.chronotif.Tutorial.shown(it, false) }
        app.chronotif.Tutorial.pause()
    }

    private fun overlays(root: ViewGroup) = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<SearchOverlay>()

    @Test fun opensStraightIntoSearchAndFinishesWhenItCloses() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView as ViewGroup
        val search = overlays(root).single()
        search.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue("Closing Search closes its window", activity.isFinishing)
        controller.destroy()
    }

    private fun texts(v: android.view.View): List<String> = when (v) {
        is android.widget.EditText -> emptyList()
        is android.widget.TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun typingShowsTheAnswerFirstAndTheWebLast() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val search = overlays(controller.get().window.decorView as ViewGroup).single()
        val field = generateSequence(listOf<android.view.View>(search)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.ifEmpty { null }
        }.flatten().filterIsInstance<android.widget.EditText>().single()
        field.setText("12*7.5+3")
        idleUntil { texts(search).any { it.startsWith("Search the web for") } }
        val shown = texts(search).filter { it.isNotBlank() }
        val answer = shown.indexOf("= 93")
        val web = shown.indexOfFirst { it.startsWith("Search the web for") }
        assertTrue("The answer shows: $shown", answer >= 0)
        assertTrue("The web row is last: $shown", web > answer && web == shown.lastIndex)
        controller.destroy()
    }

    /** Types [text], then waits for its results: they're matched on a background thread, ending with the web row. */
    private fun typeInto(search: SearchOverlay, text: String) {
        generateSequence(listOf<android.view.View>(search)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.ifEmpty { null }
        }.flatten().filterIsInstance<android.widget.EditText>().single().setText(text)
        idleUntil { texts(search).any { text.trim() in it } }
    }

    private fun idleUntil(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    private fun shownWith(locked: Boolean, hideContent: Boolean = false): List<String> {
        Prefs.searchContacts = true
        Prefs.lockHideContent = hideContent
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(locked)
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val search = overlays(controller.get().window.decorView as ViewGroup).single()
        typeInto(search, "budi")
        val shown = texts(search).filter { it.isNotBlank() }
        controller.destroy()
        return shown
    }

    @Test fun contactsAndEventsShowWhileLocked() {
        val shown = shownWith(locked = true)
        assertTrue("Locked, content not hidden: nothing waits for unlock: $shown",
            shown.none { it == "Unlock to see all results" })
    }

    @Test fun contactsAndEventsWaitForUnlockWhenTheLockScreenHidesContent() {
        val shown = shownWith(locked = true, hideContent = true)
        assertTrue("Locked, content hidden: asks to unlock, before the web row: $shown",
            shown.indexOf("Unlock to see all results") == shown.lastIndex - 1)
    }

    @Test fun nothingIsHiddenOnceUnlocked() {
        assertTrue(shownWith(locked = false).none { it == "Unlock to see all results" })
    }

    @Test fun startsWithTheQueryCarriedOverFromTheLockScreen() {
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context, "12*2")).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val search = overlays(controller.get().window.decorView as ViewGroup).single()
        idleUntil { "= 24" in texts(search) }
        val shown = texts(search)
        assertTrue("$shown", "= 24" in shown)
        controller.destroy()
    }

    private fun shownFor(text: String): List<String> {
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val search = overlays(controller.get().window.decorView as ViewGroup).single()
        typeInto(search, text)
        val shown = texts(search).filter { it.isNotBlank() }
        controller.destroy()
        return shown
    }

    /**
     * In the Tutorial's Search part: types [query], taps the row showing [row] once it's there,
     * and returns what Search said instead of opening it.
     */
    private fun tapInTutorialSearch(query: String, row: String): CharSequence? {
        val run = app.chronotif.Tutorial.start(context, app.chronotif.Tutorial.Part.SEARCH)
        app.chronotif.Tutorial.shown(app.chronotif.Tutorial.Surface.SCREEN, true)
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val search = overlays(controller.get().window.decorView as ViewGroup).single()
            typeInto(search, query)
            fun find() = generateSequence(listOf<android.view.View>(search)) { level ->
                level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.ifEmpty { null }
            }.flatten().lastOrNull { v -> v.hasOnClickListeners() && texts(v).any { it == row } }
            // Contacts and events load on a background thread.
            var target = find()
            repeat(100) {
                if (target != null) return@repeat
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
                target = find()
            }
            app.chronotif.Tutorial.next(context)
            target!!.performClick()
            assertNull("Nothing opens", shadowOf(context as Application).nextStartedActivity)
            assertSame("The run goes on", run, app.chronotif.Tutorial.run)
            return org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
        } finally {
            controller.destroy()
            app.chronotif.Tutorial.Surface.entries.forEach { app.chronotif.Tutorial.shown(it, false) }
            app.chronotif.Tutorial.pause()
        }
    }

    @Test fun inTheTutorialsSearchPartTheWebRowSaysWhatWouldOpenInstead() {
        assertEquals("In the tutorial nothing opens. Otherwise your browser would.",
            tapInTutorialSearch("example.com", "Open example.com"))
    }

    /** One event an hour from now, as the calendar provider lists it. */
    class OneEvent : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?,
        ): android.database.Cursor {
            val begin = System.currentTimeMillis() + 3_600_000
            return android.database.MatrixCursor(projection).apply { addRow(arrayOf<Any>(1L, "Dentist", begin, begin + 1_800_000, 0)) }
        }
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun inTheTutorialsSearchPartAnEventSaysTheCalendarWouldOpen() {
        Prefs.searchCalendar = true
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        Robolectric.setupContentProvider(OneEvent::class.java, android.provider.CalendarContract.AUTHORITY)
        assertEquals("In the tutorial nothing opens. Otherwise your calendar would.",
            tapInTutorialSearch("dentist", "Dentist"))
    }

    /** An app in the launcher, as LauncherApps lists it. */
    private fun launcherApp(label: String) {
        val user = android.os.Process.myUserHandle()
        val info = android.content.pm.ActivityInfo().apply {
            packageName = "test.${label.lowercase().replace(' ', '.')}"
            name = "$packageName.Main"
            nonLocalizedLabel = label
            applicationInfo = android.content.pm.ApplicationInfo()
            applicationInfo.packageName = packageName
        }
        val internal = org.robolectric.util.ReflectionHelpers.callConstructor(
            Class.forName("android.content.pm.LauncherActivityInfoInternal"),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.pm.ActivityInfo::class.java, info),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Class.forName("android.content.pm.IncrementalStatesInfo"), null),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.os.UserHandle::class.java, user),
        )
        val launcherInfo = org.robolectric.util.ReflectionHelpers.callConstructor(
            android.content.pm.LauncherActivityInfo::class.java,
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Context::class.java, context),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Class.forName("android.content.pm.LauncherActivityInfoInternal"), internal),
        )
        shadowOf(context.getSystemService(android.content.pm.LauncherApps::class.java)).addActivity(user, launcherInfo)
    }

    // Robolectric names an app by its package.
    @Test fun manyMatchingAppsShowAFewThenMoreOnRequest() {
        repeat(10) { launcherApp("Alpha $it") }
        val controller = Robolectric.buildActivity(SearchActivity::class.java, SearchActivity.intent(context)).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val search = overlays(controller.get().window.decorView as ViewGroup).single()
            typeInto(search, "alpha")
            // The apps load on a background thread too; Search matches again once they're in.
            idleUntil { texts(search).any { it.startsWith("test.alpha") } }
            val shown = texts(search)
            assertEquals("A few apps show: $shown", app.chronotif.core.AppMatch.SHOWN, shown.count { it.startsWith("test.alpha") })
            val more = context.getString(app.chronotif.R.string.search_show_more, 10 - app.chronotif.core.AppMatch.SHOWN)
            assertTrue("The rest are a tap away: $shown", more in shown)
            fun views(v: android.view.View): List<android.view.View> =
                listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            views(search).filterIsInstance<android.widget.TextView>().first { it.text.toString() == more }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val all = texts(search)
            assertEquals("All of them after Show more: $all", 10, all.count { it.startsWith("test.alpha") })
            assertTrue(all.none { it.startsWith("Show more") })
        } finally {
            controller.destroy()
        }
    }

    @Test fun currencyOffersToTurnOnRatesFirst() {
        Prefs.currencyRates = false
        assertTrue(shownFor("100 usd to idr").contains("Convert currencies"))
    }

    @Test fun currencyConvertsWithSavedRates() {
        Prefs.currencyRates = true
        Prefs.exchangeRates = "2026-11-18;USD=1.05,IDR=17325"
        Prefs.exchangeRatesFetchedAt = System.currentTimeMillis()
        val shown = shownFor("100 usd to idr")
        assertTrue("$shown", "1,650,000 IDR" in shown)
        assertTrue("$shown", "100.00 USD =" in shown)
    }
}
