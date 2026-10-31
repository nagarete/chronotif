// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.chronotif.ui.CardPreview
import app.chronotif.ui.ImageViewer
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AttachmentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val me = Person.Builder().setName("Me").setKey("me").build()
    private val sender = Person.Builder().setName("Sender").setKey("sender").build()
    private val photo = Uri.parse("content://example.chat/photo.jpg")
    private val clip = Uri.parse("content://example.chat/clip.mp4")

    @Before
    fun setup() {
        context.getSharedPreferences("chronotif", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.init(context)
        val ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.connect(emptyArray(), ranking)
    }

    @After
    fun cleanup() {
        NotifStore.disconnect()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun message(text: String, time: Long, mime: String? = null, uri: Uri? = null) =
        Notification.MessagingStyle.Message(text, time, sender).apply { if (mime != null) setData(mime, uri) }

    private fun post(vararg messages: Notification.MessagingStyle.Message): Entry {
        val style = Notification.MessagingStyle(me)
        messages.forEach(style::addMessage)
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification("example.chat", "example.chat", 1, null, 1000, 0, 0,
            n, Process.myUserHandle(), 1000)
        val ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.post(sbn, ranking)
        return NotifStore.get(sbn.key)!!
    }

    private fun <T> withPanel(block: (Activity, NotifList, LinearLayout) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            activity.setContentView(container)
            val list = NotifList(object : NotifList.Host {
                override val context: Context = activity
                override fun launched() {}
                override fun guarded(action: () -> Unit) = action()
            }, container, onLock = false)
            return block(activity, list, container)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun all(v: View): List<View> =
        listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun viewer(activity: Activity) = all(activity.window.decorView).filterIsInstance<ImageViewer>().firstOrNull()
    private fun preview(activity: Activity) = all(activity.window.decorView).filterIsInstance<CardPreview>().firstOrNull()

    private fun shown(v: View): List<String> = when {
        v.visibility != View.VISIBLE -> emptyList()
        v is TextView -> listOf(v.text.toString())
        v is ViewGroup -> (0 until v.childCount).flatMap { shown(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** Press and hold the first card, then tap the Preview's big image. */
    private fun openFromPreview(activity: Activity, container: View, description: Int = R.string.view_image) {
        all(container).filterIsInstance<app.chronotif.ui.CardView>().first().performLongClick()
        val p = preview(activity)!!
        (all(p).first { it.contentDescription == context.getString(description) }.parent as View).performClick()
    }

    @Test
    fun eachMessageKeepsItsOwnAttachment() {
        val e = post(message("look", 100, "image/jpeg", photo), message("nice right", 200), message("pdf", 300, "application/pdf", photo))
        val c = Content.of(context, e, redact = false)
        assertEquals(listOf(photo, null, null), c.attachments.map { it?.uri })
        assertTrue(Content.of(context, e, redact = true).attachments.isEmpty())
    }

    @Test
    fun aPhotoIsAThumbnailThatPressAndHoldShowsLargeAndTheViewerPagesAllOfThem() {
        post(message("first", 100, "image/jpeg", photo), message("second", 200, "image/png", photo))
        withPanel { activity, list, container ->
            list.render()
            val thumbs = all(container).filter { it.contentDescription == context.getString(R.string.view_image) }
            assertEquals("The collapsed stack shows the latest message's photo as a thumbnail", 1, thumbs.size)
            assertTrue("and counts the other photo inside", shown(container).contains("+1"))

            all(container).filterIsInstance<app.chronotif.ui.CardView>().first().performLongClick()
            val preview = preview(activity)
            assertNotNull("Press and hold holds the card up on its own", preview)
            assertTrue(shown(preview!!).contains("second"))
            (all(preview).first { it.contentDescription == context.getString(R.string.view_image) }.parent as View).performClick()

            val viewer = viewer(activity)
            assertNotNull(viewer)
            val texts = all(viewer!!).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
            assertTrue("Opens on the tapped image, among all the conversation's", texts.contains("2 of 2"))
            assertTrue(texts.containsAll(listOf("Save", "Share")))
            assertTrue("Viewing isn't a check", NotifStore.newCount() == 1)

            assertTrue("Back closes the viewer first", app.chronotif.ui.Overlay.dismissTop(container))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(viewer(activity))
            assertNotNull(preview(activity))
            assertTrue(app.chronotif.ui.Overlay.dismissTop(container))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(preview(activity))
        }
    }

    @Test
    fun replyTypesInsideThePreview() {
        val input = android.app.RemoteInput.Builder("reply").setLabel("Reply").build()
        val pi = android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent("reply"), android.app.PendingIntent.FLAG_MUTABLE)
        val style = Notification.MessagingStyle(me).addMessage(message("look", 100, "image/jpeg", photo))
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .addAction(Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input).build())
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification("example.chat", "example.chat", 3, null, 1000, 0, 0, n, Process.myUserHandle(), 1000)
        val ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.post(sbn, ranking)
        withPanel { activity, list, container ->
            list.render()
            all(container).filterIsInstance<app.chronotif.ui.CardView>().first().performLongClick()
            val p = preview(activity)!!
            all(p).filterIsInstance<TextView>().first { it.text.toString() == "Reply" }.performClick()
            assertTrue("The reply box opens in the Preview",
                all(p).any { it is android.widget.EditText && it.visibility == View.VISIBLE && (it.parent as View).visibility == View.VISIBLE })
        }
    }

    @Test
    fun anImageWithNoTextStillShowsAndSavesWithAConcreteType() {
        // As TikTok sends a DM: empty text, a wildcard type.
        val e = post(message("", 100, "image/*", photo))
        withPanel { _, list, container ->
            list.render()
            assertEquals(1, all(container).count { it.contentDescription == context.getString(R.string.view_image) })
        }
        val a = Content.of(context, e, redact = false).attachments.single()!!
        assertEquals("image/jpeg", Viewable(a, null).mime)
    }

    @Test
    fun theViewerClosesWhenItsNotificationGoesAway() {
        val e = post(message("look", 100, "image/jpeg", photo))
        withPanel { activity, list, container ->
            list.render()
            openFromPreview(activity, container)
            assertNotNull(viewer(activity))
            NotifStore.remove(e.key, null)
            list.render()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(viewer(activity))
            assertNull("The Preview closes too", preview(activity))
        }
    }

    @Test
    fun anImageMessageWithNoTextAtAllKeepsItsImage() {
        val bundle = android.os.Bundle().apply {
            putParcelable("sender_person", sender)
            putCharSequence("sender", "Sender")
            putLong("time", 100)
            putParcelable("uri", photo)
            putString("type", "image/jpeg")
        }
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(Notification.MessagingStyle(me).addMessage("placeholder", 50, sender))
            .build()
        n.extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(bundle))
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification("example.chat", "example.chat", 2, null, 1000, 0, 0, n, Process.myUserHandle(), 1000)
        val ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.post(sbn, ranking)
        val c = Content.of(context, NotifStore.get(sbn.key)!!, redact = false)
        assertEquals(listOf(photo), c.attachments.map { it?.uri })
        assertEquals(listOf("Sender"), c.lines.map { it.toString() })
    }

    @Test
    fun videoStillsOfferOpenInAppInsteadOfSave() {
        post(message("clip", 100, "video/mp4", clip))
        withPanel { activity, list, container ->
            list.render()
            openFromPreview(activity, container, R.string.video)
            val texts = all(viewer(activity)!!).filterIsInstance<TextView>()
                .filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
            assertTrue(texts.contains("Open in app"))
            assertFalse(texts.contains("Save"))
            assertFalse(texts.contains("Share"))
        }
    }
}
