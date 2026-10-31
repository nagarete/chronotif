// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AvatarTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun icon() = Icon.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

    private val groupIcon = icon()
    private val senderIcon = icon()
    private val myIcon = icon()
    private val largeIcon = icon()
    private val me = Person.Builder().setName("Me").setKey("me").setIcon(myIcon).build()
    private val sender = Person.Builder().setName("Sender").setKey("sender").setIcon(senderIcon).build()

    private fun chat(
        conversationIcon: Icon? = null,
        from: Person? = sender,
        large: Icon? = null,
        reply: Boolean = false,
        group: Boolean = false,
    ): Notification {
        val style = Notification.MessagingStyle(me)
        if (from != null) style.addMessage("hi", 100, from)
        if (reply) style.addMessage("hey", 200, me)
        if (group) style.setConversationTitle("Group").setGroupConversation(true)
        return Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setLargeIcon(large)
            .setStyle(style)
            .build()
            .also { n -> conversationIcon?.let { n.extras.putParcelable("android.conversationIcon", it) } }
    }

    @Test
    fun conversationIconComesFirst() {
        assertSame(groupIcon, Content.avatar(chat(conversationIcon = groupIcon, large = largeIcon)))
    }

    @Test
    fun oneToOneChatPrefersTheSenderPhotoOverTheLargeIcon() {
        assertSame(senderIcon, Content.avatar(chat(large = largeIcon)))
    }

    @Test
    fun groupPrefersItsPictureOverTheLatestSender() {
        assertSame(groupIcon, Content.avatar(chat(large = groupIcon, group = true)))
    }

    @Test
    fun groupWithoutAPictureShowsTheLatestSender() {
        assertSame(senderIcon, Content.avatar(chat(group = true)))
    }

    @Test
    fun ownRepliesNeverBecomeTheAvatar() {
        assertSame(senderIcon, Content.avatar(chat(reply = true)))
    }

    @Test
    fun largeIconWhenNoPhotoIsSupplied() {
        val noPhoto = Person.Builder().setName("Sender").setKey("sender").build()
        assertSame(largeIcon, Content.avatar(chat(from = noPhoto, large = largeIcon)))
    }

    @Test
    fun noAvatarWithoutAnyPicture() {
        val noPhoto = Person.Builder().setName("Sender").setKey("sender").build()
        assertNull(Content.avatar(chat(from = noPhoto)))
    }

    @Test
    fun plainTextChatsUseTheirLargeIcon() {
        val n = Notification.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("Sender").setContentText("hi")
            .setLargeIcon(largeIcon)
            .build()
        assertSame(largeIcon, Content.avatar(n))
    }

    @Test
    fun otherNotificationsHaveNoAvatar() {
        val n = Notification.Builder(context, "mail")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mail").setContentText("hi")
            .setLargeIcon(largeIcon)
            .build()
        assertNull(Content.avatar(n))
    }
}
