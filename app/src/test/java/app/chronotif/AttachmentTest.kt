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

    @Test
    fun eachMessageKeepsItsOwnAttachment() {
        val e = post(message("look", 100, "image/jpeg", photo), message("nice right", 200), message("pdf", 300, "application/pdf", photo))
        val c = Content.of(context, e, redact = false)
        assertEquals(listOf(photo, null, null), c.attachments.map { it?.uri })
        assertTrue(Content.of(context, e, redact = true).attachments.isEmpty())
    }

    @Test
    fun tappingAnAttachmentOpensTheViewerAtThatImageAndBackClosesIt() {
        post(message("first", 100, "image/jpeg", photo), message("second", 200, "image/png", photo))
        withPanel { activity, list, container ->
            list.render()
            val images = all(container).filter { it.contentDescription == context.getString(R.string.view_image) }
            assertEquals("The collapsed stack shows the latest message's image", 1, images.size)
            (images[0].parent as View).performClick()

            val viewer = viewer(activity)
            assertNotNull(viewer)
            val texts = all(viewer!!).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
            assertTrue("Opens on the tapped image, among all the conversation's", texts.contains("2 of 2"))
            assertTrue(texts.containsAll(listOf("Save", "Share")))
            assertTrue("Viewing isn't a check", NotifStore.newCount() == 1)

            assertTrue(ImageViewer.dismissIn(container))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(viewer(activity))
        }
    }

    @Test
    fun videoStillsOfferOpenInAppInsteadOfSave() {
        post(message("clip", 100, "video/mp4", clip))
        withPanel { activity, list, container ->
            list.render()
            val video = all(container).first { it.contentDescription == context.getString(R.string.video) }
            (video.parent as View).performClick()
            val texts = all(viewer(activity)!!).filterIsInstance<TextView>()
                .filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
            assertTrue(texts.contains("Open in app"))
            assertFalse(texts.contains("Save"))
            assertFalse(texts.contains("Share"))
        }
    }
}
