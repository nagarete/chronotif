// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.chronotif.Feedback
import app.chronotif.Prefs
import app.chronotif.R
import org.junit.After
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
import java.io.IOException
import java.util.concurrent.Executor

/** Settings' Send feedback page: what it posts, and what's left in the form after a send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedbackFormTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<String>()
    private var answer: () -> Int = { 200 }
    private val worker = Feedback.worker
    private val transport = Feedback.transport

    @Before fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        Feedback.Draft.clear()
        Feedback.Draft.result = null
        Feedback.Draft.sending = false
        Feedback.worker = Executor { it.run() }
        Feedback.transport = { body -> sent += body; answer() }
    }

    @After fun restore() {
        Feedback.worker = worker
        Feedback.transport = transport
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class Page(val activity: SetupActivity, val root: View) {
        fun shown() = all(root).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
        fun button(name: Int) = all(root).filterIsInstance<TextView>().first {
            it.isShown && it.text == activity.getString(name) && it.hasOnClickListeners()
        }
        fun field(hint: Int) = all(root).filterIsInstance<EditText>().first { it.hint == activity.getString(hint) }

        private fun all(v: View): List<View> =
            listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    }

    private fun open(): Page {
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        val root = activity.window.decorView
        val name = activity.getString(R.string.setup_feedback)
        all(root).first { it is android.widget.LinearLayout && it.hasOnClickListeners() &&
            all(it).any { t -> t is TextView && t.text == name } }.performClick()
        idle()
        return Page(activity, root)
    }

    @Test fun `Send waits for a message, then posts it with the phone's details`() {
        val page = open()
        page.button(R.string.feedback_send).performClick()
        idle()
        assertTrue("Nothing to send yet", sent.isEmpty())

        page.button(R.string.feedback_kind_problem).performClick()
        page.field(R.string.feedback_text_hint).setText("The panel opens slowly")
        page.field(R.string.feedback_contact_hint).setText("me@example.com")
        page.button(R.string.feedback_send).performClick()
        idle()

        val body = sent.single()
        assertTrue(body, body.startsWith("""{"source":"app","kind":"problem","text":"The panel opens slowly","contact":"me@example.com","details":{"Chronotif":"""))
        assertTrue(body, body.contains("\"Android\":") && body.contains("\"Phone\":") && body.contains("\"System\":"))
        assertTrue(page.shown().contains(page.activity.getString(R.string.feedback_sent)))
        assertEquals("", page.field(R.string.feedback_text_hint).text.toString())
        assertEquals("", page.field(R.string.feedback_contact_hint).text.toString())
    }

    @Test fun `turning the details off leaves them out`() {
        val page = open()
        page.field(R.string.feedback_text_hint).setText("Hi")
        all(page.root).first { it.accessibilityDelegate is M3Switch.RowAccessibility }.performClick()
        page.button(R.string.feedback_send).performClick()
        idle()
        assertEquals("""{"source":"app","text":"Hi"}""", sent.single())
    }

    @Test fun `a failed send says why and keeps the message`() {
        answer = { throw IOException("offline") }
        val page = open()
        page.field(R.string.feedback_text_hint).setText("Still here")
        page.button(R.string.feedback_send).performClick()
        idle()
        assertTrue(page.shown().contains(page.activity.getString(R.string.feedback_offline)))
        assertEquals("Still here", page.field(R.string.feedback_text_hint).text.toString())

        answer = { 429 }
        page.button(R.string.feedback_send).performClick()
        idle()
        assertTrue(page.shown().contains(page.activity.getString(R.string.feedback_too_many)))
        assertFalse(page.shown().contains(page.activity.getString(R.string.feedback_offline)))
    }

    @Test fun `the draft outlasts a page switch`() {
        val page = open()
        page.field(R.string.feedback_text_hint).setText("Half written")
        val again = open()
        assertEquals("Half written", again.field(R.string.feedback_text_hint).text.toString())
    }
}
