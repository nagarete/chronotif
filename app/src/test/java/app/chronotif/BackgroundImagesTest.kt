// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Pictures, avatars and action icons that need reading load in the background, never while a card binds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundImagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val resIcon get() = Icon.createWithResource(context, android.R.drawable.ic_dialog_email)

    private fun entry(id: Int, n: Notification): Entry {
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(context.packageName, context.packageName, id, null, 1000, 0, 0,
            n, Process.myUserHandle(), 1000L + id)
        return Entry(sbn, Ranking(), 1000)
    }

    private fun builder() = Notification.Builder(context, "test")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Title")

    private fun settle() {
        Apps.loopers.forEach { shadowOf(it).idle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aBigPictureIconLoadsInTheBackground() {
        val e = entry(1, builder().setStyle(Notification.BigPictureStyle().bigPicture(resIcon)).build())
        var heard = false
        assertNull(Apps.picture(context, e) { heard = it != null })
        settle()
        assertEquals(true, heard)
        assertNotNull(Apps.picture(context, e))
    }

    @Test
    fun aBigPictureBitmapIsShownAtOnce() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val e = entry(2, builder().setStyle(Notification.BigPictureStyle().bigPicture(bitmap)).build())
        assertNotNull(Apps.picture(context, e))
    }

    @Test
    fun noPictureTellsThePreviewAtOnce() {
        val e = entry(3, builder().build())
        var heard = true
        assertNull(Apps.picture(context, e) { heard = it != null })
        assertEquals(false, heard)
    }

    @Test
    fun aResourceAvatarLoadsInTheBackground() {
        val sender = Person.Builder().setName("Sender").setKey("sender").setIcon(resIcon).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("Me").build()).addMessage("hi", 100, sender)
        val e = entry(4, builder().setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).build())
        assertNull(Apps.avatar(context, e))
        settle()
        assertNotNull(Apps.avatar(context, e))
    }

    @Test
    fun actionIconsLoadOnceAndEachCallerGetsItsOwnCopy() {
        val pi = PendingIntent.getBroadcast(context, 0, Intent("test").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val a = Notification.Action.Builder(resIcon, "", pi).build()
        // Another post of the same notification, with an equal icon.
        val b = Notification.Action.Builder(resIcon, "", pi).build()
        assertNull(Apps.actionIcon(context, a))
        settle()
        val first = Apps.actionIcon(context, a)
        val second = Apps.actionIcon(context, b)
        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)
    }

    @Test
    fun anUpdatedAppsNameIsReadAgain() {
        val pkg = "example.updated"
        assertEquals("", Apps.label(context, pkg))
        settle()
        assertEquals(pkg, Apps.label(context, pkg).toString())
        Apps.packageChanged(pkg)
        assertEquals("", Apps.label(context, pkg))
        settle()
        assertEquals(pkg, Apps.label(context, pkg).toString())
    }
}
