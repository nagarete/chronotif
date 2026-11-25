// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.ui.CardView
import app.chronotif.ui.NotifList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.util.ReflectionHelpers

/**
 * The Panel's list draws whatever [CardSource] it's given and sends what the user does there,
 * so a stand-in source (the Tutorial's) never touches the phone's notifications, Android or the
 * real last check. Made-up notifications are refused by the real one too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardSourceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        val ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.connect(emptyArray(), ranking)
    }

    private fun opens() = PendingIntent.getActivity(context, 0, Intent(context, Activity::class.java), PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun entry(pkg: String, title: String, time: Long, id: Int = 1): Entry {
        val n = Notification.Builder(context, "samples").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText("$title text").setContentIntent(opens()).build()
        val sbn = StatusBarNotification(pkg, pkg, id, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), time)
        return Entry(sbn, Ranking(), time)
    }

    /** A stand-in source that records what reaches it. */
    private class Samples(val entries: MutableList<Entry>, override val lastCheck: Long) : CardSource {
        val opened = ArrayList<String>()
        val dismissed = ArrayList<String>()
        var checkedUpTo = 0L
        override val connected = true
        override fun visible() = entries.toList()
        override fun get(key: String) = entries.firstOrNull { it.key == key }
        override fun hiddenMessages(key: String) = emptySet<String>()
        override fun markChecked(seenUpTo: Long) {
            checkedUpTo = seenUpTo
        }
        override fun addListener(l: () -> Unit) {}
        override fun removeListener(l: () -> Unit) {}
        override fun open(context: Context, e: Entry): Boolean {
            opened += e.key
            return true
        }
        override fun action(context: Context, e: Entry, a: Notification.Action) = false
        override fun reply(context: Context, e: Entry, a: Notification.Action, text: CharSequence) = false
        override fun dismissAll(entries: List<Entry>) {
            dismissed += entries.map { it.key }
            this.entries.removeAll(entries.toSet())
        }
        override fun hideMessage(key: String, messageId: String) {}
        override fun snooze(e: Entry, until: Long) {}
        override fun dismissMedia(context: Context, e: Entry) = false
    }

    private fun views(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    private fun texts(v: View) = views(v).filterIsInstance<TextView>().map { it.text.toString() }

    @Test
    fun theListDrawsOnlyItsSourceSplitByThatSourcesOwnCheck() {
        // A real notification on the phone, which must not show.
        NotifStore.post(StatusBarNotification("example.bank", "example.bank", 9, null, Process.myUid(), 0, 0,
            Notification.Builder(context, "x").setContentTitle("Real secret").build(), Process.myUserHandle(), 5_000),
            ReflectionHelpers.callConstructor(RankingMap::class.java,
                ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>())))
        val pkg = Entry.SAMPLE_PREFIX + "chat"
        val samples = Samples(mutableListOf(entry(pkg, "Old sample", 1_000, 1), entry(pkg, "New sample", 3_000, 2)), lastCheck = 2_000)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false, source = samples)
            list.render()
            val shown = texts(container)
            assertFalse("The phone's own notifications stay hidden", shown.contains("Real secret"))
            assertTrue("New by the source's own check", shown.indexOf("New sample") < shown.indexOf("EARLIER"))
            assertTrue(shown.indexOf("EARLIER") < shown.indexOf("Old sample"))

            val newCard = views(container).filterIsInstance<CardView>().first { "New sample" in texts(it) }
            newCard.performClick()
            assertEquals("Opening goes to the source", listOf(samples.entries[1].key), samples.opened)
            assertNull("and launches nothing", shadowOf(activity).nextStartedActivity)

            newCard.onSwiped?.invoke()
            assertEquals(1, samples.dismissed.size)
            assertNotNull("The real notification is untouched", NotifStore.visible().firstOrNull { it.pkg == "example.bank" })
            assertEquals("and so is the real check", 0L, Prefs.lastCheck)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aMadeUpNotificationIsNeverActedOnByTheRealSource() {
        val sample = entry(Entry.SAMPLE_PREFIX + "chat", "Sample", 1_000)
        assertTrue(sample.isSample)
        assertFalse(entry("example.chat", "Real", 1_000).isSample)
        assertFalse("Nothing opens", CardSource.Real.open(context, sample))
        assertNull(shadowOf(context as Application).nextStartedActivity)
        val action = Notification.Action.Builder(null, "Reply", opens())
            .addRemoteInput(android.app.RemoteInput.Builder("text").build()).build()
        assertFalse(CardSource.Real.action(context, sample, action))
        assertFalse(CardSource.Real.reply(context, sample, action, "hi"))
        assertFalse(CardSource.Real.dismissMedia(context, sample))
        // No crash when an app's reply button has no intent.
        val noIntent = Notification.Action.Builder(null, "Reply", null)
            .addRemoteInput(android.app.RemoteInput.Builder("text").build()).build()
        assertFalse(Act.reply(context, noIntent, "hi"))
    }

    /** Delivery is told apart from opening, so a button that works in the background still counts as tapped. */
    @Test
    fun aButtonThatWorksInTheBackgroundIsSentWithoutOpeningAnything() {
        fun button(pi: PendingIntent) = Notification.Action.Builder(null, "Mark as read", pi).build()
        val background = PendingIntent.getBroadcast(context, 1, Intent("test.read"), PendingIntent.FLAG_IMMUTABLE)
        assertEquals(Act.Sent.SENT, Act.action(context, button(background)))
        assertEquals(Act.Sent.OPENS, Act.action(context, button(opens())))
        background.cancel()
        assertEquals(Act.Sent.FAILED, Act.action(context, button(background)))
    }
}
