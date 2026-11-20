// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TikTok's LIVE alerts carry the host's photo only inside their custom layout, with no large icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomPictureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun live(id: Int, photoPx: Int): Entry {
        val layout = RemoteViews(context.packageName, android.R.layout.activity_list_item)
        layout.setImageViewBitmap(android.R.id.icon, Bitmap.createBitmap(photoPx, photoPx, Bitmap.Config.ARGB_8888))
        val n = Notification.Builder(context, "live")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("meo · Following")
            .setContentText("invites you to watch their LIVE!")
            .setCustomContentView(layout)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .build()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(context.packageName, context.packageName, id, null, 1000, 0, 0,
            n, Process.myUserHandle(), 1000)
        return Entry(sbn, Ranking(), 1000)
    }

    /** Lets the background loads finish and land on the main thread. */
    private fun settle() {
        Apps.loopers.forEach { shadowOf(it).idle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aCustomLayoutsPhotoStandsInForTheMissingLargeIcon() {
        val e = live(1, photoPx = 100)
        assertNull(Apps.largeIcon(context, e)) // inflated in the background, never during binding
        settle()
        val photo = Apps.largeIcon(context, e) as BitmapDrawable
        assertEquals(100, photo.bitmap.width)
    }

    @Test
    fun anIconSizedImageIsNoPhoto() {
        val e = live(2, photoPx = 24)
        Apps.largeIcon(context, e)
        settle()
        assertNull(Apps.largeIcon(context, e))
    }
}
