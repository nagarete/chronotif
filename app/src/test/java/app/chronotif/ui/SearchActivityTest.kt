// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import app.chronotif.Prefs
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
        shadowOf(Looper.getMainLooper()).idle()
        val shown = texts(search).filter { it.isNotBlank() }
        val answer = shown.indexOf("= 93")
        val web = shown.indexOfFirst { it.startsWith("Search the web for") }
        assertTrue("The answer shows: $shown", answer >= 0)
        assertTrue("The web row is last: $shown", web > answer && web == shown.lastIndex)
        controller.destroy()
    }

    private fun typeInto(search: SearchOverlay, text: String) {
        generateSequence(listOf<android.view.View>(search)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map(v::getChildAt) else emptyList() }.ifEmpty { null }
        }.flatten().filterIsInstance<android.widget.EditText>().single().setText(text)
        shadowOf(Looper.getMainLooper()).idle()
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
        val shown = texts(overlays(controller.get().window.decorView as ViewGroup).single())
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
