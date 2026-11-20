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
import android.media.session.PlaybackState
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
import app.chronotif.ui.dp
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
        DemoMode.set(context, false)
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
    private fun post(n: Notification, time: Long = 1000, id: Int = 1, pkg: String = "example.chat"): Entry {
        val sbn = StatusBarNotification(pkg, pkg, id, null, 1000, 0, 0,
            n, Process.myUserHandle(), time)
        NotifStore.post(sbn, ranking)
        return NotifStore.get(sbn.key)!!
    }

    @Test
    fun appSearchSuggestsAppsThatNotifiedButNotRunningWork() {
        val chat = post(notification(listOf("hi" to 1000L)), time = 1000, id = 1, pkg = "example.chat")
        val mail = post(notification(listOf("hello again" to 3000L)), time = 3000, id = 2, pkg = "example.mail")
        val steps = Notification.Builder(context, "steps")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("1,204 steps")
            .setOngoing(true)
            .build()
        val running = post(steps, time = 5000, id = 3, pkg = "example.steps")
        assertEquals(Kind.RUNNING, running.kind)
        val last = Prefs.lastNotified
        assertEquals(setOf(chat.appId, mail.appId), last.keys)
        assertTrue("The app that notified later is newer", last.getValue(mail.appId) > last.getValue(chat.appId))
    }

    @Test
    fun demoModeShowsOnlyTheMadeUpDemoAppsAndStaysOnAfterARestart() {
        val real = post(notification("real chat"), time = 1000, id = 1, pkg = "example.chat")
        post(notification("demo chat"), time = 2000, id = 2, pkg = DemoMode.PREFIX + "chat")
        DemoMode.set(context, true)
        DemoMode.init(context)
        assertNull("Real notifications aren't even kept", NotifStore.get(real.key))
        @Suppress("DEPRECATION")
        val arriving = StatusBarNotification("example.mail", "example.mail", 3, null, 1000, 0, 0,
            notification("another real one"), Process.myUserHandle(), 3000)
        NotifStore.post(arriving, ranking)
        assertNull("One arriving in demo mode isn't kept either", NotifStore.get(arriving.key))
        withLockList { list, container ->
            list.render()
            val shown = shownTexts(container)
            assertTrue(shown.contains("demo chat"))
            assertFalse("A real notification must never reach a recording", shown.contains("real chat"))
        }
        assertEquals(1, NotifStore.newCount())
        DemoMode.set(context, false)
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
            assertTrue("The checked message moved to Earlier",
                shown.indexOf("second") < shown.indexOf("EARLIER") && shown.indexOf("EARLIER") < shown.indexOf("first"))
        }
    }

    private fun plain(title: String, time: Long, id: Int, pkg: String) = post(
        Notification.Builder(context, "updates").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText("$title text").build(), time, id, pkg)

    private fun <T> withLockView(block: (LockActivity) -> T): T {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val controller = Robolectric.buildActivity(LockActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            return block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Text the lock-screen view shows: visible, not faded out, and with every parent the same. */
    private fun seen(view: View): List<String> = when {
        view.visibility != View.VISIBLE || view.alpha == 0f -> emptyList()
        view is TextView -> listOf(view.text.toString())
        view is ViewGroup -> (0 until view.childCount).flatMap { seen(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun LockActivity.scroll() = views(window.decorView).filterIsInstance<android.widget.ScrollView>().single()

    @Test
    fun theLockScreenViewRestsWithNewAsAPileThatUnfoldsAsItScrolls() {
        plain("Old news", 500, 1, "example.news")
        Prefs.markChecked(1000)
        plain("Parcel", 2000, 2, "example.shop")
        plain("Meeting", 3000, 3, "example.calendar")
        plain("Lunch?", 4000, 4, "example.chat")
        withLockView { activity ->
            var shown = seen(activity.window.decorView)
            assertTrue("The newest New card shows whole", shown.contains("Lunch?"))
            assertFalse("The rest of New is folded under it", shown.contains("Meeting") || shown.contains("Parcel"))
            assertTrue("with a count of what's folded", shown.contains("2 more"))
            assertFalse("Earlier stays folded too", shown.contains("Old news"))

            val scroll = activity.scroll()
            scroll.scrollTo(0, 10_000)
            shadowOf(Looper.getMainLooper()).idle()
            shown = seen(activity.window.decorView)
            assertEquals("Unfolded in the same order, then Earlier",
                listOf("Lunch?", "Meeting", "Parcel", "Old news"),
                shown.filter { it in setOf("Lunch?", "Meeting", "Parcel", "Old news") })
            assertFalse(shown.contains("2 more"))
        }
    }

    @Test
    fun withNothingNewTheLockScreenViewRestsAsTheClockAndAnEarlierLine() {
        plain("Old news", 500, 1, "example.news")
        plain("Older news", 400, 2, "example.mail")
        Prefs.markChecked(1000)
        withLockView { activity ->
            val shown = seen(activity.window.decorView)
            assertTrue(shown.contains("2 earlier"))
            assertFalse(shown.contains("Old news"))
        }
    }

    @Test
    fun backFoldsTheOpenListIntoThePileThenLeavesTheLockScreenView() {
        plain("Lunch?", 4000, 1, "example.chat")
        plain("Meeting", 3000, 2, "example.calendar")
        withLockView { activity ->
            val scroll = activity.scroll()
            scroll.scrollTo(0, 10_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(seen(activity.window.decorView).contains("Meeting"))
            activity.back()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(activity.isFinishing)
            assertEquals(0, scroll.scrollY)
            assertFalse(seen(activity.window.decorView).contains("Meeting"))
            activity.back()
            assertTrue(activity.isFinishing)
        }
    }

    private fun song(title: String, playing: Boolean, id: Int = 9): Entry {
        val art = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF2E7D32.toInt()) }
        val n = Notification.Builder(context, "media").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText("Artist").setLargeIcon(art)
            .setStyle(Notification.MediaStyle()).setOngoing(playing).build()
        return post(n, 2500, id, "example.music")
    }

    /** The large artwork the lock-screen view draws over the list, if it shows. */
    private fun LockActivity.bigArtwork(): android.widget.ImageView? {
        val player = views(window.decorView).filterIsInstance<MediaPlayerView>().first()
        val art = player.artwork ?: return null
        return views(window.decorView).filterIsInstance<android.widget.ImageView>()
            .firstOrNull { it !== player.thumb && it.drawable === art && it.isShownInTree() }
    }

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
    fun aMediaPlayersArtworkRestsLargeAboveThePileAndShrinksIntoThePlayerAsItScrolls() {
        plain("Old news", 500, 1, "example.news")
        Prefs.markChecked(1000)
        plain("Lunch?", 4000, 2, "example.chat")
        song("Garden", playing = false)
        withLockView { activity ->
            val big = activity.bigArtwork()
            assertNotNull("The artwork rests large", big)
            assertTrue("bigger than the thumbnail", big!!.width > activity.dp(64) * 2)
            val shown = seen(activity.window.decorView)
            assertTrue("The player shows under it", shown.contains("Garden"))
            assertTrue("and New's Pile under the player", shown.contains("Lunch?"))
            assertFalse("Earlier stays folded", shown.contains("Old news"))
            val backdrop = views(activity.window.decorView).filterIsInstance<android.widget.ImageView>()
                .first { it.layoutParams.width == -1 && it.layoutParams.height == -1 }
            assertEquals("A blurred copy stands in for the wallpaper", View.VISIBLE, backdrop.visibility)

            activity.scroll().scrollTo(0, 10_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Scrolled open, the artwork is back in the player", activity.bigArtwork())
            assertEquals("The backdrop stays", View.VISIBLE, backdrop.visibility)
            assertTrue(seen(activity.window.decorView).contains("Old news"))

            activity.back()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNotNull("Back brings it back", activity.bigArtwork())
        }
    }

    @Test
    @Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp")
    fun aPrivateLockScreenShowsNoLargeArtwork() {
        Prefs.lockHideContent = true
        plain("Lunch?", 4000, 2, "example.chat")
        song("Garden", playing = true)
        withLockView { activity ->
            assertNull(activity.bigArtwork())
            assertFalse(seen(activity.window.decorView).contains("Garden"))
        }
    }

    @Test
    fun playingMediaBringsUpTheLockScreenViewButPausedMediaDoesNot() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        Prefs.lockMode = Prefs.LOCK_WHEN_NEW
        song("Garden", playing = false)
        assertFalse(LockPolicy.shouldShow(context))
        song("Garden", playing = true)
        assertTrue(LockPolicy.shouldShow(context))
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
    fun tappingAnOpenStacksNameClosesIt() {
        post(notification(listOf("Jiah" to 1100L, "Gawd damn" to 1200L)), 1200)
        withPanelList { list, container ->
            list.render()
            views(container).filterIsInstance<app.chronotif.ui.StackView>().single().card.performClick()
            val showLess = views(container).filterIsInstance<TextView>().single { it.text == "Show less" }
            val header = showLess.parent as ViewGroup
            assertFalse("Touches on the name fall through to the header", header.getChildAt(0).isClickable)
            header.performClick()
            val shown = shownTexts(container)
            assertFalse(shown.contains("Show less"))
            assertFalse("Collapsed again", shown.contains("Jiah"))
            assertTrue(shown.contains("Gawd damn"))
        }
    }

    @Test
    fun withSwipeToClearOffASwipeLeavesTheCardInPlace() {
        val n = Notification.Builder(context, "updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("Your order shipped")
            .build()
        post(n, pkg = "example.shop")
        fun swipe(card: app.chronotif.ui.CardView): Boolean {
            measure(card, 1000)
            fun ev(action: Int, x: Float) = android.view.MotionEvent.obtain(0, 0, action, x, 50f, 0)
            card.swipe.onTouch(card, ev(android.view.MotionEvent.ACTION_DOWN, 100f))
            card.swipe.onTouch(card, ev(android.view.MotionEvent.ACTION_MOVE, 400f))
            card.swipe.onTouch(card, ev(android.view.MotionEvent.ACTION_MOVE, 800f))
            assertTrue("A sideways drag is taken, so it never ends as a tap",
                card.swipe.onTouch(card, ev(android.view.MotionEvent.ACTION_UP, 800f)))
            return card.swipe.dismissing
        }
        withPanelList { list, container ->
            Prefs.swipeToClear = false
            list.render()
            val card = views(container).filterIsInstance<app.chronotif.ui.CardView>().single()
            assertFalse(swipe(card))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals("It springs back", 0f, card.translationX)
            Prefs.swipeToClear = true
            assertTrue("Turning it back on needs no rebuild", swipe(card))
        }
    }

    @Test
    fun aSnoozedNotificationComesBackAsNew() {
        val e = post(notification(listOf("see you at 8" to 900L)), 1000)
        Prefs.markChecked(5000)
        assertEquals(0, NotifStore.newCount())
        Prefs.snoozed(e.key, until = 6000)
        NotifStore.remove(e.key, ranking)
        // Android posts it again unchanged, with its old post time.
        val back = post(notification(listOf("see you at 8" to 900L)), 1000)
        assertTrue(back.alertTime > 5000)
        assertEquals(1, NotifStore.newCount())
        // Only once: a later quiet update is an update, not another return.
        NotifStore.remove(e.key, ranking)
        assertTrue(post(notification(listOf("see you at 8" to 900L)), 1000).alertTime < 5000)
    }

    @Test
    fun thePreviewOffersSnoozeAndClearAndClearHidesThatMessage() {
        val e = post(notification(listOf("one" to 1100L, "two" to 1200L)), 1200)
        withPanelList { list, container ->
            (container.context as Activity).setContentView(container)
            list.render()
            views(container).filterIsInstance<app.chronotif.ui.StackView>().single().card.performClick()
            val card = views(container).filterIsInstance<app.chronotif.ui.CardView>()
                .first { "one" in texts(it) && "two" !in texts(it) }
            card.performLongClick()
            val root = container.rootView as ViewGroup
            fun button(label: String) = views(root).filterIsInstance<TextView>().single { it.text == label && it.isShownInTree() }
            button("Snooze").performClick()
            assertTrue(shownTexts(root).containsAll(listOf("15 minutes", "1 hour", "3 hours", "Tomorrow morning", "Pick a time…")))
            button("Pick a time…").performClick()
            assertTrue("A time picker, and a button saying when",
                views(root).any { it is android.widget.TimePicker && it.isShownInTree() } &&
                    shownTexts(root).any { it.startsWith("Snooze until") })
            assertFalse("The choices replace the buttons", shownTexts(root).contains("Clear"))
            app.chronotif.ui.Overlay.dismissTop(root)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            card.performLongClick()
            button("Clear").performClick()
            val one = Content.of(context, e, false).messages.first { it.line.endsWith("one") }.id
            assertEquals(setOf(one), NotifStore.hiddenMessages(e.key))
            assertNotNull("The rest of the conversation stays", NotifStore.get(e.key))
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
    fun aDmNamedOnlyByItsShortcutTakesTheShortcutLabelAndShowsItsSenderLine() {
        // As TikTok sends it: every name field reads "sent you a message", the text is empty,
        // and only the conversation shortcut knows who it's from.
        val them = Person.Builder().setName("sent you a message").setKey("them").build()
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("sent you a message")
            .setStyle(Notification.MessagingStyle(Person.Builder().setName("sent you a message").build())
                .addMessage("", 100, them))
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification("example.chat", "example.chat", 7, null, 1000, 0, 0,
            n, Process.myUserHandle(), 1000)
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "dm")
            .setShortLabel("[samuel irvan] caramel").build()
        val ranking = object : Ranking() {
            override fun getConversationShortcutInfo() = shortcut
        }
        val c = Content.of(context, Entry(sbn, ranking, 1000), false)
        assertEquals("[samuel irvan] caramel", c.title.toString())
        assertEquals(listOf("sent you a message"), c.lines.map { it.toString() })
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
    fun inactiveMediaCanUseRealSystemOrAppDismissalButActiveMediaCannot() {
        fun playback(state: Int) = PlaybackState.Builder()
            .setState(state, 0L, 1f).build()
        val paused = playback(PlaybackState.STATE_PAUSED)
        val stopped = playback(PlaybackState.STATE_STOPPED)
        val playing = playback(PlaybackState.STATE_PLAYING)
        val buffering = playback(PlaybackState.STATE_BUFFERING)

        val clearable = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Paused track")
            .setStyle(Notification.MediaStyle())
            .build(), 2000, id = 12)
        assertEquals(Kind.MEDIA, clearable.kind)
        assertTrue(clearable.clearable)
        assertTrue(MediaDismiss.canSwipe(clearable, paused))
        assertTrue(MediaDismiss.canSwipe(clearable, stopped))
        assertFalse(MediaDismiss.canSwipe(clearable, playing))
        assertFalse(MediaDismiss.canSwipe(clearable, buffering))
        // Preserve the upstream clearable-media behaviour even without a session.
        assertTrue(MediaDismiss.canSwipe(clearable, null))

        val ongoing = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Ongoing track")
            .setStyle(Notification.MediaStyle())
            .setOngoing(true)
            .build(), 2000, id = 13)
        assertFalse(ongoing.clearable)
        assertFalse("No deleteIntent: never pretend an ongoing media card is dismissible",
            MediaDismiss.canSwipe(ongoing, paused))
        assertFalse(MediaDismiss.canSwipe(ongoing, stopped))

        val noClear = Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Protected track")
            .setStyle(Notification.MediaStyle())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }
        val protectedEntry = post(noClear, 2000, id = 14)
        assertFalse(protectedEntry.clearable)
        assertFalse(MediaDismiss.canSwipe(protectedEntry, paused))

        // Media3 apps including Apple Music may expose a deleteIntent despite
        // setting NO_CLEAR: the app itself can process an explicit user dismissal.
        val pendingDelete = android.app.PendingIntent.getBroadcast(
            context, 404, Intent("app.chronotif.test.EXPLICIT_MEDIA_DISMISS"),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val protectedWithDelete = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Protected but app-dismissable")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pendingDelete)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 15)
        assertFalse(protectedWithDelete.clearable)
        assertTrue(MediaDismiss.canSwipe(protectedWithDelete, paused))
        assertTrue(MediaDismiss.canSwipe(protectedWithDelete, stopped))
        assertFalse(MediaDismiss.canSwipe(protectedWithDelete, playing))
        assertFalse(MediaDismiss.canSwipe(protectedWithDelete, buffering))
        assertFalse(MediaDismiss.canSwipe(protectedWithDelete, null))

        // Apple Music leaves a Play action on a stale NO_CLEAR media notification
        // even when a MediaSession controller is no longer available.
        val play = Notification.Action.Builder(null, "Play", pendingDelete).build()
        val pause = Notification.Action.Builder(null, "Pause", pendingDelete).build()
        val stalePaused = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Paused Apple Music")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pendingDelete)
            .addAction(play)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 16)
        assertTrue(MediaDismiss.canSwipe(stalePaused, null))
        assertFalse(MediaDismiss.canSwipe(stalePaused, playing))
        val playingActions = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Playing Apple Music")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pendingDelete)
            .addAction(pause)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 17)
        assertFalse(MediaDismiss.canSwipe(playingActions, null))
        // Only the Play action is accepted as a fallback: both Play and Pause
        // indicate ambiguous state, which must not permit dismissal.
        val ambiguousActions = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Ambiguous media")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pendingDelete)
            .addAction(play)
            .addAction(pause)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 19)
        assertFalse(MediaDismiss.canSwipe(ambiguousActions, null))
    }

    @Test
    fun inactiveMediaCanInterceptSwipeStartingOnItsChildren() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, 408, Intent("app.chronotif.test.MEDIA_DISMISS"),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Paused player")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pending)
            .addAction(Notification.Action.Builder(null, "Play", pending).build())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 18)

        withPanelList { list, container ->
            list.render()
            val card = views(container).filterIsInstance<MediaPlayerView>().single()
            val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.measure(width, height)
            card.layout(0, 0, 400, card.measuredHeight)
            fun event(action: Int, x: Float, y: Float) =
                android.view.MotionEvent.obtain(0, 25, action, x, y, 0)
            val down = event(android.view.MotionEvent.ACTION_DOWN, 40f, 32f)
            val move = event(android.view.MotionEvent.ACTION_MOVE, 180f, 34f)
            try {
                assertFalse(card.onInterceptTouchEvent(down))
                assertTrue("Horizontal gesture over media content must be intercepted",
                    card.onInterceptTouchEvent(move))
            } finally {
                down.recycle()
                move.recycle()
            }
        }
    }

    @Test
    fun mediaSwipeLeavesVerticalScrollingAndPlayingControlsUnintercepted() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, 409, Intent("app.chronotif.test.MEDIA_VERTICAL"),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Paused player")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pending)
            .addAction(Notification.Action.Builder(null, "Play", pending).build())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 20)

        withPanelList { list, container ->
            list.render()
            val card = views(container).filterIsInstance<MediaPlayerView>().single()
            val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            card.measure(width, height)
            card.layout(0, 0, 400, card.measuredHeight)
            fun event(action: Int, x: Float, y: Float) =
                android.view.MotionEvent.obtain(0, 25, action, x, y, 0)
            val down = event(android.view.MotionEvent.ACTION_DOWN, 40f, 32f)
            val move = event(android.view.MotionEvent.ACTION_MOVE, 44f, 172f)
            try {
                assertFalse(card.onInterceptTouchEvent(down))
                assertFalse("Vertical scrolling must not become media dismissal",
                    card.onInterceptTouchEvent(move))
            } finally {
                down.recycle()
                move.recycle()
            }
        }
    }


    @Test
    fun mediaButtonTapAndSwipeFromButtonUseFullDispatchedGestures() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, 415, Intent("app.chronotif.test.MEDIA_BUTTON"),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Inactive player")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pending)
            .addAction(Notification.Action.Builder(null, "Play", pending).build())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 45)

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            activity.setContentView(container)
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false)
            list.render()
            val card = views(container).filterIsInstance<MediaPlayerView>().single()
            card.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, 400, card.measuredHeight)
            val button = views(card).filterIsInstance<android.widget.ImageView>()
                .first { it.contentDescription == "Play" }
            fun center(child: View): Pair<Float, Float> {
                var x = child.width / 2f
                var y = child.height / 2f
                var v = child
                while (v !== card) {
                    x += v.left
                    y += v.top
                    v = v.parent as View
                }
                return x to y
            }
            fun dispatch(action: Int, x: Float, y: Float, time: Long) {
                val e = android.view.MotionEvent.obtain(0, time, action, x, y, 0)
                try { card.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            val (x, y) = center(button)
            val count = shadowOf(context as Application).broadcastIntents.size
            dispatch(android.view.MotionEvent.ACTION_DOWN, x, y, 50)
            dispatch(android.view.MotionEvent.ACTION_UP, x, y, 75)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("A tap on the media action must still send it",
                count + 1, shadowOf(context as Application).broadcastIntents.size)
            assertFalse(card.swipe.dismissing)

            dispatch(android.view.MotionEvent.ACTION_DOWN, x, y, 100)
            dispatch(android.view.MotionEvent.ACTION_MOVE, x + 100, y, 125)
            dispatch(android.view.MotionEvent.ACTION_MOVE, x + 270, y, 145)
            dispatch(android.view.MotionEvent.ACTION_UP, x + 270, y, 165)
            assertTrue("Horizontal swipe beginning on a media action must dismiss",
                card.swipe.dismissing)
            assertEquals("Swipe must not click the control again",
                count + 1, shadowOf(context as Application).broadcastIntents.size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun seekBarDragViaDispatchTouchEventDoesNotDismissTheMediaPlayer() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, 416, Intent("app.chronotif.test.MEDIA_SEEK"),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Paused player")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pending)
            .addAction(Notification.Action.Builder(null, "Play", pending).build())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 46)

        withPanelList { list, container ->
            list.render()
            val card = views(container).filterIsInstance<MediaPlayerView>().single()
            // No session in this test, so expose a seekable UI to exercise the
            // actual nested touch routing without relying on Robolectric media.
            val row: LinearLayout = ReflectionHelpers.getField(card, "seekRow")
            val seek: android.widget.SeekBar = ReflectionHelpers.getField(card, "seek")
            row.visibility = View.VISIBLE
            seek.visibility = View.VISIBLE
            seek.isEnabled = true
            seek.max = 1000
            card.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, 400, card.measuredHeight)

            var x = seek.left.toFloat()
            var y = seek.height / 2f
            var parent = seek.parent as View
            while (parent !== card) {
                x += parent.left
                y += parent.top
                parent = parent.parent as View
            }
            fun dispatch(action: Int, px: Float, time: Long) {
                val e = android.view.MotionEvent.obtain(0, time, action, px, y, 0)
                try { card.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            val start = x + seek.width * 0.2f
            val finish = x + seek.width * 0.8f
            val before = seek.progress
            dispatch(android.view.MotionEvent.ACTION_DOWN, start, 200)
            dispatch(android.view.MotionEvent.ACTION_MOVE, finish, 225)
            dispatch(android.view.MotionEvent.ACTION_UP, finish, 250)
            assertTrue("SeekBar must receive the drag", seek.progress > before)
            assertFalse("Seeking must not trigger media dismissal", card.swipe.dismissing)
        }
    }

    @Test
    fun successfulSendWithoutNotificationRemovalRestoresCardAfterTimeout() {
        val pending = android.app.PendingIntent.getBroadcast(
            context, 417, Intent("app.chronotif.test.MEDIA_NO_REMOVAL"),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val entry = post(Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Persistent player")
            .setStyle(Notification.MediaStyle())
            .setDeleteIntent(pending)
            .addAction(Notification.Action.Builder(null, "Play", pending).build())
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }, 2000, id = 47)

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            activity.setContentView(container)
            var dispatched = 0
            val gateway = object : MediaDismiss.Gateway {
                override fun current(key: String) = NotifStore.get(key)
                override fun playbackState(
                    context: Context, token: android.media.session.MediaSession.Token,
                ): Int? = null
                override fun listenerConnected() = true
                override fun cancel(entry: Entry) = fail("Protected media must not use listener cancel")
                override fun send(intent: android.app.PendingIntent) {
                    intent.send() // Real successful PendingIntent, no Android removal callback.
                    dispatched++
                }
            }
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false, mediaDismiss = { ctx, e ->
                MediaDismiss.dismiss(ctx, e, gateway)
            })
            list.render()
            val card = views(container).filterIsInstance<MediaPlayerView>().single()
            ReflectionHelpers.setField(card.swipe, "dismissing", true)
            card.translationX = 300f
            card.alpha = 0f
            list.onDismiss(entry)
            assertEquals(1, dispatched)
            assertNotNull(NotifStore.get(entry.key))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(950))
            assertSame(card, views(container).filterIsInstance<MediaPlayerView>().single())
            assertEquals(0f, card.translationX, 0.01f)
            assertEquals(1f, card.alpha, 0.01f)
            assertFalse(card.swipe.dismissing)
        } finally {
            controller.pause().stop().destroy()
        }
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

    private fun replyable(messages: List<Pair<String, Long>>): Notification {
        val input = android.app.RemoteInput.Builder("reply").setLabel("Reply").build()
        val pi = android.app.PendingIntent.getBroadcast(context, 0, Intent("reply"), android.app.PendingIntent.FLAG_MUTABLE)
        return Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(Notification.MessagingStyle(me).also { style ->
                messages.forEach { (text, time) -> style.addMessage(text, time, sender) }
            })
            .addAction(Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input).build())
            .build()
    }

    private fun <T> withPanelList(block: (NotifList, LinearLayout) -> T): T = withLockList { _, container ->
        val list = NotifList(object : NotifList.Host {
            override val context: Context = container.context
            override fun launched() {}
            override fun guarded(action: () -> Unit) = action()
        }, container, onLock = false)
        block(list, container)
    }

    private fun replyBoxShown(container: View) =
        views(container).any { it is android.widget.EditText && it.isShownInTree() }

    private fun View.isShownInTree(): Boolean {
        var v: View? = this
        while (v != null) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }

    /** A lock-screen list that records what had to wait for an unlock. */
    private fun <T> withRecordingLockList(block: (NotifList, LinearLayout, MutableList<String>) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity)
            val unlocks = ArrayList<String>()
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) { unlocks += "unlock" }
            }, container, onLock = true)
            return block(list, container, unlocks)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aReplyTypesRightOnTheLockScreen() {
        val e = post(replyable(listOf("one" to 1100L)), 1100)
        withRecordingLockList { list, container, unlocks ->
            list.render()
            val id = "${e.key}#${Content.of(context, e, false).messages.single().id}"
            list.onAction(e, id, 0)
            assertTrue("The reply box opens on the lock screen", replyBoxShown(container))
            assertTrue("Replying doesn't ask to unlock: $unlocks", unlocks.isEmpty())
        }
    }

    @Test
    fun aReplyTheAppWantsUnlockedWaitsForAnUnlock() {
        val input = android.app.RemoteInput.Builder("reply").setLabel("Reply").build()
        val pi = android.app.PendingIntent.getBroadcast(context, 0, Intent("reply"), android.app.PendingIntent.FLAG_MUTABLE)
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(Notification.MessagingStyle(me).also { it.addMessage("one", 1100L, sender) })
            .addAction(Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input)
                .setAuthenticationRequired(true).build())
            .build()
        val e = post(n, 1100)
        withRecordingLockList { list, container, unlocks ->
            list.render()
            val id = "${e.key}#${Content.of(context, e, false).messages.single().id}"
            list.onAction(e, id, 0)
            assertFalse(replyBoxShown(container))
            assertEquals(listOf("unlock"), unlocks)
        }
    }

    @Test
    fun runningShowsOnTheLockScreenBehindItsPill() {
        post(notification(listOf("hi" to 1100L)), 1100, id = 1)
        post(Notification.Builder(context, "service").setContentTitle("Sync is running")
            .setOngoing(true).build(), id = 2, pkg = "example.sync")
        withLockList { list, container ->
            list.render()
            assertTrue(texts(container).contains("1 running"))
            views(container).first { it is TextView && it.text.toString() == "1 running" }.performClick()
            assertTrue(texts(container).contains("Sync is running"))
        }
    }

    @Test
    fun aDraftReplySurvivesAnotherMessageArriving() {
        val e = post(replyable(listOf("one" to 1100L)), 1100)
        withPanelList { list, container ->
            list.render()
            val id = "${e.key}#${Content.of(context, e, false).messages.single().id}"
            list.onAction(e, id, 0)
            assertTrue(replyBoxShown(container))
            post(replyable(listOf("one" to 1100L, "two" to 1200L)), 1200)
            list.render()
            assertTrue("The card being replied to stays open with its editor", replyBoxShown(container))
            assertTrue(shownTexts(container).containsAll(listOf("one", "two")))
        }
    }

    @Test
    fun aReplyHandoffFindsTheNewestMessageStillShown() {
        val e = post(replyable(listOf("older" to 1100L, "newer" to 1200L)), 1200)
        NotifStore.hideMessage(e.key, Content.of(context, e, false).messages.last().id)
        withPanelList { list, container ->
            list.focus(e.key, 0)
            list.render()
            assertTrue(shownTexts(container).contains("older"))
            assertTrue("The reply editor opens on the newest message left", replyBoxShown(container))
        }
    }

    @Test
    fun aQuietlyRepostedChatSortsByItsMessagesNotItsRepost() {
        // WhatsApp reposts yesterday's chat; a mail arrived in between. The list follows the times its cards show.
        post(notification(listOf("old chat" to 1000L)), time = 5000, id = 1)
        val mail = Notification.Builder(context, "mail")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mail").setContentText("newer mail")
            .build()
        post(mail, time = 3000, id = 2, pkg = "example.mail")
        Prefs.markChecked(10_000)
        withPanelList { list, container ->
            list.render()
            val shown = shownTexts(container)
            assertTrue(shown.toString(), shown.indexOf("newer mail") in 0 until shown.indexOf("old chat"))
        }
    }

    @Test
    fun earlierKeepsOneAppsNotificationsInTimeOrderAroundAnotherApps() {
        // Two mails from one app with another app's chat between them: the old mail stays below the chat.
        fun mail(text: String) = Notification.Builder(context, "mail")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mail").setContentText(text)
            .build()
        post(mail("old mail"), time = 1000, id = 1, pkg = "example.mail")
        post(notification(listOf("chat" to 3000L)), time = 3000, id = 2)
        post(mail("new mail"), time = 4000, id = 3, pkg = "example.mail")
        Prefs.markChecked(10_000)
        withPanelList { list, container ->
            list.render()
            val shown = shownTexts(container)
            val order = listOf("new mail", "chat", "old mail").map { shown.indexOf(it) }
            assertTrue(shown.toString(), order.all { it >= 0 } && order == order.sorted())
            assertTrue(views(container).none { it is app.chronotif.ui.StackView && it.visibility == View.VISIBLE })
        }
    }

    @Test
    fun earlierStacksAnAppsNeighbouringCardsOnlyWhenTheyArrivedCloseTogether() {
        fun task(text: String) = Notification.Builder(context, "tasks")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task").setContentText(text)
            .build()
        val hour = 3_600_000L
        val base = 10 * hour
        post(task("old task"), time = base - 4 * hour, id = 1, pkg = "example.tasks")
        post(task("older task"), time = base - 4 * hour - 60_000, id = 2, pkg = "example.tasks")
        post(task("recent task"), time = base - 120_000, id = 3, pkg = "example.tasks")
        post(task("newest task"), time = base, id = 4, pkg = "example.tasks")
        // Single-message chats from one app stack like any other card.
        post(notification(listOf("sticker" to base - hour)), time = base - hour, id = 5)
        post(notification(listOf("hi" to base - hour - 1000)), time = base - hour - 1000, id = 6)
        Prefs.markChecked(base + 1)
        withPanelList { list, container ->
            list.render()
            val stacks = views(container).filterIsInstance<app.chronotif.ui.StackView>().filter { it.isShownInTree() }
            assertEquals(3, stacks.size)
            val shown = shownTexts(container)
            // Collapsed: each stack shows only its newest card, in time order.
            val order = listOf("newest task", "sticker", "old task").map { shown.indexOf(it) }
            assertTrue(shown.toString(), order.all { it >= 0 } && order == order.sorted())
            assertTrue(shown.toString(), listOf("recent task", "hi", "older task").none { it in shown })

            // Opening the recent stack shows its cards in place, still above the chats.
            stacks[0].card.performClick()
            val opened = shownTexts(container)
            val inPlace = listOf("newest task", "recent task", "sticker", "old task").map { opened.indexOf(it) }
            assertTrue(opened.toString(), inPlace.all { it >= 0 } && inPlace == inPlace.sorted())
            assertTrue(opened.toString(), "older task" !in opened)
        }
    }

    @Test
    fun aConversationWithEveryMessageHiddenIsNotNew() {
        val e = post(notification(listOf("one" to 1100L, "two" to 1200L)), 1200)
        assertEquals(1, NotifStore.newCount())
        Content.of(context, e, false).messages.forEach { NotifStore.hideMessage(e.key, it.id) }
        assertEquals(0, NotifStore.newCount())
    }

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

    @Test
    fun anAppsNameLoadsInTheBackgroundSoTheListNeverWaitsForIt() {
        val n = Notification.Builder(context, "updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentText("Your parcel is on its way")
            .build()
        post(n, pkg = "example.parcels")
        withPanelList { list, container ->
            list.render()
            assertTrue(texts(container).toString(), texts(container).any { "parcel is on its way" in it })
            assertFalse("The name isn't read while drawing", texts(container).any { "example.parcels" in it })
            Apps.loopers.forEach { shadowOf(it).idle() }
            shadowOf(Looper.getMainLooper()).idle()
            list.render()
            // An app the package manager doesn't know is named by its package.
            assertTrue(texts(container).toString(), texts(container).any { "example.parcels" in it })
        }
    }

    private fun <T> withPanelView(block: (app.chronotif.ui.PanelView) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val panel = app.chronotif.ui.PanelView(activity, object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, blur = true, windowDims = true) {}
            activity.setContentView(panel)
            shadowOf(Looper.getMainLooper()).idle()
            return block(panel)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun aStatusBarPullThatSlidesBackIsNoCheck() {
        post(notification("hi"), time = 1000)
        val before = Prefs.lastCheck
        withPanelView { panel ->
            panel.shown(pulling = true)
            panel.pull(0.3f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            panel.dismissed()
        }
        assertEquals("Peeking leaves New alone", before, Prefs.lastCheck)
    }

    @Test
    fun aPullThatOpensCountsFromWhenItsCardsFirstShowed() {
        post(notification("hi"), time = 1000)
        val before = Prefs.lastCheck
        withPanelView { panel ->
            panel.shown(pulling = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertTrue("The cards come in during the pull", texts(panel).any { "hi" in it })
            panel.settle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            panel.dismissed()
            assertEquals("A glance shorter than a moment", before, Prefs.lastCheck)
            // Pulled for a second before letting go: that second counts.
            panel.shown(pulling = true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            panel.settle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            panel.dismissed()
        }
        assertNotEquals("On screen for a moment, and opened", before, Prefs.lastCheck)
    }

    /** The installed app looks updated rather than newly installed, so What's new has something to show. */
    private fun pretendUpdated() {
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        info.firstInstallTime = 1
        info.lastUpdateTime = 2
    }

    private fun gotIt(root: View): View? {
        val label = context.getString(R.string.whats_new_done)
        fun find(v: View): View? {
            if (v is TextView && v.text.toString() == label && v.isShown) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(root)
    }

    @Test
    fun whatsNewOverTheCardsIsNoCheck() {
        post(notification("hi"), time = 1000)
        pretendUpdated()
        val before = Prefs.lastCheck
        withPanelView { panel ->
            panel.shown()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNotNull("The first open after an update shows What's new", gotIt(panel.rootView))
            panel.dismissed()
        }
        assertEquals("Closed while What's new covered the cards", before, Prefs.lastCheck)
        assertNull("It shows once", app.chronotif.ui.WhatsNewSheet.pending(context))
    }

    @Test
    fun theCheckCountsFromWhenWhatsNewCloses() {
        post(notification("hi"), time = 1000)
        pretendUpdated()
        val before = Prefs.lastCheck
        withPanelView { panel ->
            panel.shown()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            gotIt(panel.rootView)!!.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            panel.dismissed()
            assertEquals("Half a second of cards after the sheet", before, Prefs.lastCheck)
            panel.reset()
            panel.shown()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertNull("Not again on the next open", gotIt(panel.rootView))
            panel.dismissed()
        }
        assertNotEquals("Cards on screen for a moment", before, Prefs.lastCheck)
    }

    // --- Panel header ---

    /** Runs [block] with the header's clock-and-date block, the start of its row; the gear is at the end. */
    private fun <T> withPanelHeader(block: (app.chronotif.ui.PanelActivity, LinearLayout) -> T): T {
        val controller = Robolectric.buildActivity(app.chronotif.ui.PanelActivity::class.java).create()
        try {
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val scroll = (0 until root.childCount).map { root.getChildAt(it) }
                .filterIsInstance<android.widget.ScrollView>().single()
            val header = (scroll.getChildAt(0) as ViewGroup).getChildAt(0) as LinearLayout
            val row = header.getChildAt(0) as LinearLayout
            return block(controller.get(), row.getChildAt(0) as LinearLayout)
        } finally {
            controller.destroy()
        }
    }

    private fun measure(v: View, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, width, v.measuredHeight)
    }

    @Test
    fun thePanelHeaderShowsAClockWithTheDateUnderItInsteadOfTheTitle() = withPanelHeader { _, header ->
        assertFalse(texts(header).toString(), texts(header).contains(context.getString(R.string.tile_label)))
        val row = header.getChildAt(0) as app.chronotif.ui.ClockDateRow
        val clock = row.getChildAt(0) as android.widget.TextClock
        assertEquals("h:mm", clock.format12Hour)
        assertEquals("H:mm", clock.format24Hour)
        val sp48 = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 48f, context.resources.displayMetrics)
        assertEquals(sp48, clock.textSize, 0.01f)
        measure(header, 1080)
        assertFalse(row.beside)
        val date = row.getChildAt(1)
        assertTrue("The date sits under the clock", date.top >= clock.bottom)
    }

    @Test
    fun withTheClockOffThePanelHeaderShowsOnlyTheDate() {
        Prefs.headerClock = false
        Prefs.headerWeekday = false
        withPanelHeader { _, header ->
            assertEquals(1, header.childCount)
            val date = header.getChildAt(0) as android.widget.TextClock
            assertEquals(app.chronotif.ui.PanelHeader.datePattern(), date.format24Hour.toString())
            assertFalse("No weekday", date.format24Hour.contains("E"))
        }
    }

    @Test
    fun aDateBesideTheClockMovesUnderItWhenItDoesNotFit() {
        Prefs.headerDateBeside = true
        Prefs.headerDateAlign = Prefs.DATE_ALIGN_BOTTOM
        withPanelHeader { _, header ->
            val row = header.getChildAt(0) as app.chronotif.ui.ClockDateRow
            val clock = row.getChildAt(0)
            val date = row.getChildAt(1)
            measure(header, 4000)
            assertTrue(row.beside)
            assertTrue("Beside the clock", date.left >= clock.right)
            assertEquals("Bottom lines up with the clock's baseline",
                clock.top + clock.baseline, date.top + date.baseline)
            measure(header, clock.measuredWidth + 8)
            assertFalse(row.beside)
            assertTrue("Under the clock, not cut off", date.top >= clock.bottom)
        }
    }

    @Test
    fun theHeaderDateFollowsTheLocalesOrder() {
        Prefs.headerYear = true
        assertEquals("EEEE, MMMM d, y", app.chronotif.ui.PanelHeader.datePattern(java.util.Locale.US))
        assertEquals("EEEE, d. MMMM y", app.chronotif.ui.PanelHeader.datePattern(java.util.Locale.GERMANY))
    }

    @Test
    fun tappingTheClockOpensAlarmsAndTheDateOpensTheCalendarThenThePanelCloses() {
        withPanelHeader { activity, header ->
            val row = header.getChildAt(0) as ViewGroup
            assertTrue(row.getChildAt(0).performClick())
            assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS,
                shadowOf(activity).nextStartedActivity.action)
            assertTrue(activity.isFinishing)
        }
        withPanelHeader { activity, header ->
            val row = header.getChildAt(0) as ViewGroup
            row.getChildAt(1).performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertTrue(opened.data.toString(), opened.data.toString().startsWith("content://com.android.calendar/time/"))
            assertTrue(activity.isFinishing)
        }
    }

    @Test
    fun aClockAppThatRefusesItsAlarmsScreenIsOpenedPlainInstead() {
        val pm = shadowOf(context.packageManager)
        val alarms = android.content.ComponentName("com.example.clock", "com.example.clock.Alarms")
        val main = android.content.ComponentName("com.example.clock", "com.example.clock.Main")
        pm.addActivityIfNotPresent(alarms)
        pm.addIntentFilterForActivity(alarms, android.content.IntentFilter(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            .apply { addCategory(Intent.CATEGORY_DEFAULT) })
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(main, android.content.IntentFilter(Intent.ACTION_MAIN)
            .apply { addCategory(Intent.CATEGORY_LAUNCHER) })

        val intents = app.chronotif.ui.PanelHeader.alarmsIntents(context)
        assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS, intents[0].action)
        assertEquals("com.example.clock", intents[1].`package` ?: intents[1].component?.packageName)
        assertTrue(intents[1].hasCategory(Intent.CATEGORY_LAUNCHER))
    }

    @Test
    fun tappingTheClockWithNoClockAppDoesNothing() {
        withPanelHeader { activity, header ->
            shadowOf(activity.application).checkActivities(true)
            val row = header.getChildAt(0) as ViewGroup
            row.getChildAt(0).performClick()
            assertFalse("The panel stays open", activity.isFinishing)
        }
    }

    @Test
    fun theHeaderClockAndDateReadAsButtonsToTalkBack() = withPanelHeader { _, header ->
        val row = header.getChildAt(0) as ViewGroup
        listOf(0 to R.string.header_open_alarms, 1 to R.string.header_open_calendar).forEach { (i, label) ->
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
            row.getChildAt(i).onInitializeAccessibilityNodeInfo(info)
            assertEquals(android.widget.Button::class.java.name, info.className)
            assertTrue(info.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK &&
                it.label == context.getString(label) })
        }
    }

    private fun gear(start: LinearLayout): View? = (start.parent as ViewGroup).let { row ->
        (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.ImageView>().singleOrNull()
    }

    @Test
    fun tappingTheGearOpensAndroidSettingsThenThePanelCloses() = withPanelHeader { activity, start ->
        val gear = gear(start)!!
        assertTrue(gear.performClick())
        assertEquals(android.provider.Settings.ACTION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertTrue(activity.isFinishing)
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        gear.onInitializeAccessibilityNodeInfo(info)
        assertEquals(android.widget.Button::class.java.name, info.className)
        assertEquals(context.getString(R.string.header_open_settings), gear.contentDescription)
    }

    @Test
    fun theGearSitsAtTheHeadersEndAndCanBeTurnedOff() {
        withPanelHeader { _, start ->
            val row = start.parent as ViewGroup
            measure(row, 1080)
            assertEquals("At the end", 1080, gear(start)!!.right)
            assertTrue("After the clock and date", gear(start)!!.left >= start.right)
        }
        Prefs.headerSettings = false
        withPanelHeader { _, start -> assertNull(gear(start)) }
    }
}
