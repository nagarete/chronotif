// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.ui

import android.app.Activity
import android.app.Notification
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.FrameLayout
import app.chronotif.Content
import app.chronotif.Entry
import app.chronotif.Viewable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** A closed Panel is kept hidden (ADR 0009); its Media player mustn't keep ticking or listening. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPlayerViewTest {
    private object NoCallbacks : CardCallbacks {
        override fun onTap(e: Entry, c: Content) {}
        override fun onToggleExpand(e: Entry, id: String) {}
        override fun onAction(e: Entry, id: String, index: Int) {}
        override fun onSendReply(e: Entry, id: String, index: Int, text: CharSequence) {}
        override fun onDismiss(e: Entry) {}
        override fun onViewImages(anchor: View, e: Entry, items: List<Viewable>, start: Int) {}
        override fun onPreview(anchor: View, e: Entry, c: Content, at: Long) {}
    }

    @Test
    fun hidingTheKeptPanelStopsThePlayerAndShowingItResumes() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val session = MediaSession(activity, "test")
        try {
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            session.isActive = true
            val n = Notification.Builder(activity, "media")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Song")
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
                .build()
            @Suppress("DEPRECATION")
            val sbn = StatusBarNotification(activity.packageName, activity.packageName, 1, null, 1000, 0, 0,
                n, Process.myUserHandle(), 1000L)
            val e = Entry(sbn, Ranking(), 1000)

            val root = FrameLayout(activity)
            activity.setContentView(root)
            val player = MediaPlayerView(activity, NoCallbacks)
            player.bind(e, Content.of(activity, e, false))
            root.addView(player)
            // Robolectric never tells the window it's visible, as the system does once it's on screen.
            ReflectionHelpers.callInstanceMethod<Unit>(root.rootView.parent, "dispatchAppVisibility",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(player.isListening)

            // What OverlayPanel.remove() does to the kept Panel's root.
            root.visibility = View.INVISIBLE
            assertFalse(player.isListening)

            root.visibility = View.VISIBLE
            assertTrue(player.isListening)

            root.removeView(player)
            assertFalse(player.isListening)
        } finally {
            session.release()
        }
    }
}
