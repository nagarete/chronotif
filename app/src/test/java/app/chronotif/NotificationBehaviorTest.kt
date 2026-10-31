// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.core.Attention
import app.chronotif.core.Kind
import app.chronotif.ui.LockActivity
import app.chronotif.ui.MediaPlayerView
import app.chronotif.ui.NotifList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationBehaviorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ranking: RankingMap
    private val sender = Person.Builder().setName("Sender").setKey("sender").build()
    private val me = Person.Builder().setName("Me").setKey("me").build()

    @Before
    fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        LockSession.reset()
        ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.connect(emptyArray(), ranking)
    }

    @After
    fun cleanup() {
        NotifStore.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun notification(vararg texts: String): Notification =
        notification(texts.mapIndexed { i, text -> text to 100L + i })

    private fun notification(messages: List<Pair<String, Long>>): Notification =
        Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MessagingStyle(me).also { style ->
                messages.forEach { (text, time) -> style.addMessage(text, time, sender) }
            })
            .build()

    private fun <T> withLockList(block: (NotifList, LinearLayout) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = true)
            return block(list, container)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Suppress("DEPRECATION")
    private fun post(n: Notification, time: Long = 1000, id: Int = 1): Entry {
        val sbn = StatusBarNotification("example.chat", "example.chat", id, null, 1000, 0, 0,
            n, Process.myUserHandle(), time)
        NotifStore.post(sbn, ranking)
        return NotifStore.get(sbn.key)!!
    }

    @Test
    fun silentIncomingMessageReturnsToNewWhileTheCheckedOneStaysEarlier() {
        post(notification("first"))
        Prefs.markChecked(1000)
        post(notification("first", "second"), 2000)
        assertEquals("The new message must not remain trapped in Earlier", 1, NotifStore.newCount())

        withLockList { list, container ->
            list.render()
            val shown = shownTexts(container)
            assertTrue(shown.contains("second"))
            assertFalse("The checked message moved to Earlier", shown.contains("first"))
            assertTrue(shown.contains("1 earlier"))
        }
    }

    @Test
    fun newMessagesStackInNewAndOpenIntoOneCardEach() {
        post(notification(listOf("seen" to 900L)), 900)
        Prefs.markChecked(1000)
        post(notification(listOf("seen" to 900L, "Jiah" to 1100L, "Gawd damn" to 1200L)), 1200)
        withLockList { _, container ->
            NotifList(object : NotifList.Host {
                override val context: Context = container.context
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false).render()
            var shown = shownTexts(container)
            assertTrue("Collapsed to the latest, with a count", shown.containsAll(listOf("Gawd damn", "2")))
            assertFalse(shown.contains("Jiah"))

            views(container).filterIsInstance<app.chronotif.ui.StackView>().single { it.isShown || it.visibility == View.VISIBLE }
                .card.performClick()
            shown = shownTexts(container)
            assertTrue(shown.containsAll(listOf("Jiah", "Gawd damn", "Show less")))
            assertTrue("Newest first", shown.indexOf("Gawd damn") < shown.indexOf("Jiah"))
            assertTrue("The checked message sits in its own Earlier stack", shown.contains("seen"))
            assertTrue(shown.indexOf("Jiah") < shown.indexOf("seen"))
        }
    }

    @Test
    fun swipingAMessageCardHidesOnlyThatMessage() {
        val e = post(notification(listOf("one" to 1100L, "two" to 1200L)), 1200)
        val c = Content.of(context, e, false)
        NotifStore.hideMessage(e.key, c.messages.first().id)
        shadowOf(Looper.getMainLooper()).idle()
        withLockList { list, container ->
            list.render()
            val shown = shownTexts(container)
            assertTrue(shown.contains("two"))
            assertFalse(shown.contains("one"))
            assertFalse("A single remaining message is a plain card", shown.contains("2"))
        }
        assertNotNull("The notification itself stays", NotifStore.get(e.key))
    }

    @Test
    fun aTitledOneToOneChatDoesNotPrefixItsMessagesWithTheSender() {
        // X titles one-to-one chats but marks them as not a group.
        val n = notification("damn")
        n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "natan")
        n.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        assertEquals(listOf("damn"), Content.of(context, post(n), false).lines.map { it.toString() })
    }

    @Test
    fun expansionRetainsAllSuppliedMessages() {
        val messages = (1..9).map { "message $it" }
        val entry = post(notification(*messages.toTypedArray()))
        assertEquals(messages, Content.of(context, entry, false).lines.map { it.toString() })
    }

    @Test
    fun historicMessagesAreDisplayedWithoutBecomingNewArrivals() {
        val style = Notification.MessagingStyle(me)
            .addHistoricMessage(Notification.MessagingStyle.Message("old", 10, sender))
            .addMessage("latest", 100, sender)
        val n = Notification.Builder(context, "messages").setStyle(style).setOnlyAlertOnce(true).build()
        val entry = post(n)
        Prefs.markChecked(1000)
        style.addHistoricMessage(Notification.MessagingStyle.Message("older", 5, sender))
        val refreshed = post(Notification.Builder(context, "messages").setStyle(style).setOnlyAlertOnce(true).build(), 2000)
        assertEquals(listOf("older", "old", "latest"), Content.of(context, refreshed, false).lines.map { it.toString() })
        assertEquals(0, NotifStore.newCount())
        assertEquals(entry.alertTime, refreshed.alertTime)
    }

    @Test
    fun outgoingReplyAndRepostDoNotReturnAConversationToNew() {
        post(notification("incoming"))
        Prefs.markChecked(1000)
        val reply = Notification.MessagingStyle(me)
            .addMessage("incoming", 100, sender)
            .addMessage("my reply", 200, null as Person?)
        // Even an audible repost is not a new incoming message.
        val n = Notification.Builder(context, "messages").setStyle(reply).build()
        post(n, 2000)
        assertEquals(0, NotifStore.newCount())
        post(notification("incoming"), 3000)
        assertEquals(0, NotifStore.newCount())
    }

    @Test
    fun plainMessageUpdatesReturnToNewButTimestampRefreshesDoNot() {
        fun plain(text: String, time: Long) = Notification.Builder(context, "messages")
            .setCategory(Notification.CATEGORY_MESSAGE).setOnlyAlertOnce(true)
            .setContentTitle("Sender").setContentText(text).setWhen(time).build()
        post(plain("first", 1000))
        Prefs.markChecked(1000)
        post(plain("first", 2000), 2000)
        assertEquals(0, NotifStore.newCount())
        post(plain("second", 3000), 3000)
        assertEquals(1, NotifStore.newCount())
    }

    @Test
    fun quietProgressUpdateRemainsInEarlierAndRedactionHidesHistory() {
        val n = Notification.Builder(context, "work").setContentText("Downloading")
            .setOnlyAlertOnce(true).setProgress(100, 10, false).build()
        post(n)
        Prefs.markChecked(1000)
        post(Notification.Builder(context, "work").setContentText("Almost done")
            .setOnlyAlertOnce(true).setProgress(100, 90, false).build(), 2000)
        assertEquals(0, NotifStore.newCount())
        val private = post(notification("private one", "private two"), 3000)
        val content = Content.of(context, private, true)
        assertTrue(content.lines.isEmpty())
        assertFalse(content.expandable)
        assertFalse(content.text.toString().contains("private"))
    }

    @Test
    fun lockViewUsesTheKeyguardTimeoutAndStaysUntilScreenOff() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(LockActivity::class.java).setup()
        try {
            val timeout = WindowManager.LayoutParams::class.java.getField("userActivityTimeout")
                .getLong(controller.get().window.attributes)
            assertEquals("The display must sleep on the short keyguard timeout, not the normal one", 10_000L, timeout)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertFalse("Idle must not expose another stock lock-screen interval", controller.get().isFinishing)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun runningWorkStaysCollapsedBelowEverythingUntilTapped() {
        post(Notification.Builder(context, "service").setContentTitle("Sync is running")
            .setOngoing(true).build(), id = 1)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false)
            list.render()
            val shown = texts(container)
            assertTrue(shown.contains("1 running"))
            assertFalse("Running alone isn't announced as empty", shown.contains("No notifications"))
            assertFalse(shown.contains("Sync is running"))
            views(container).first { it is TextView && it.text.toString() == "1 running" }.performClick()
            assertTrue(texts(container).contains("Sync is running"))
            assertEquals(0, NotifStore.newCount())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pausedMediaStaysAMediaPlayerInsteadOfFallingIntoEarlier() {
        // Players drop the ongoing flag when paused.
        val paused = post(Notification.Builder(context, "media").setContentTitle("Song")
            .setStyle(Notification.MediaStyle()).build(), 2000)
        assertEquals(Kind.MEDIA, paused.kind)
        val s = Attention.sections(NotifStore.visible(), lastCheck = 0)
        assertEquals(listOf(paused.key), s.media.map { it.key })
        assertTrue(s.earlier.isEmpty() && s.fresh.isEmpty())
    }

    @Test
    fun mediaPlayerShowsAtTopAndKeepsTitlesOffAPrivateLockScreen() {
        post(notification("hello"), id = 1)
        post(Notification.Builder(context, "media").setContentTitle("Secret song").setContentText("Artist")
            .setStyle(Notification.MediaStyle()).build(), 2000, id = 2)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            fun list(onLock: Boolean) = LinearLayout(activity).also { container ->
                NotifList(object : NotifList.Host {
                    override val context: Context = activity
                    override fun launched() {}
                    override fun guarded(action: () -> Unit) = action()
                }, container, onLock).render()
            }
            val panel = list(onLock = false)
            assertTrue(panel.getChildAt(0) is MediaPlayerView)
            assertTrue(texts(panel).containsAll(listOf("Secret song", "Artist")))

            Prefs.lockHideContent = true
            val lock = list(onLock = true)
            assertTrue(lock.getChildAt(0) is MediaPlayerView)
            assertFalse(texts(lock).any { it.contains("Secret song") || it == "Artist" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun views(view: View): List<View> =
        if (view is ViewGroup) listOf(view) + (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else listOf(view)

    /** Text a user could see: skips views that are gone. */
    private fun shownTexts(view: View): List<String> = when {
        view.visibility != View.VISIBLE -> emptyList()
        view is TextView -> listOf(view.text.toString())
        view is ViewGroup -> (0 until view.childCount).flatMap { shownTexts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
