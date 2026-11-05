// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import app.chronotif.core.Kind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises MediaDismiss.dismiss itself, including the actual PendingIntent dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaDismissTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var ranking: RankingMap
    private val sessions = ArrayList<MediaSession>()

    @Before fun setup() {
        ranking = ReflectionHelpers.callConstructor(RankingMap::class.java,
            ReflectionHelpers.ClassParameter.from(Array<Ranking>::class.java, emptyArray<Ranking>()))
        NotifStore.connect(emptyArray(), ranking)
    }

    @After fun cleanup() {
        sessions.forEach { it.release() }
        NotifStore.disconnect()
    }

    private fun intent(id: Int) = PendingIntent.getBroadcast(
        context, id, Intent("app.chronotif.test.MEDIA_$id").setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun player(
        delete: PendingIntent? = null,
        token: MediaSession.Token? = null,
        clearable: Boolean = false,
        actions: Array<Notification.Action> = emptyArray(),
    ): Notification {
        val style = Notification.MediaStyle()
        if (token != null) style.setMediaSession(token)
        val builder = Notification.Builder(context, "media")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Player")
            .setStyle(style)
        if (delete != null) builder.setDeleteIntent(delete)
        for (action in actions) builder.addAction(action)
        return builder.build().apply {
            if (!clearable) flags = flags or Notification.FLAG_NO_CLEAR
        }
    }

    private fun post(n: Notification, id: Int = 42): Entry {
        val sbn = StatusBarNotification("example.media", "example.media", id, null,
            1000, 0, 0, n, Process.myUserHandle(), 1000L)
        NotifStore.post(sbn, ranking)
        return NotifStore.get(sbn.key)!!
    }

    private fun session(name: String): MediaSession = MediaSession(context, name).also {
        sessions += it
    }

    private class FakeGateway(
        var currentEntry: Entry?,
        var state: Int? = null,
        var connected: Boolean = true,
    ) : MediaDismiss.Gateway {
        var cancels = 0
        val sent = ArrayList<PendingIntent>()
        override fun current(key: String) = currentEntry?.takeIf { it.key == key }
        override fun playbackState(context: Context, token: MediaSession.Token) = state
        override fun listenerConnected() = connected
        override fun cancel(entry: Entry) { cancels++ }
        override fun send(intent: PendingIntent) {
            sent += intent
            intent.send()
        }
    }

    @Test fun protectedInactiveUsesLiveDeleteIntentNotListenerCancellation() {
        val old = intent(31)
        val replacement = intent(32)
        val token = session("live-action").sessionToken
        val snapshot = post(player(delete = old, token = token))
        val current = post(player(delete = replacement, token = token)) // Same key, fresh app action.
        val gateway = FakeGateway(current, PlaybackState.STATE_PAUSED)
        assertTrue(MediaDismiss.dismiss(context, snapshot, gateway))
        assertEquals(listOf(replacement), gateway.sent)
        assertEquals(0, gateway.cancels)
        assertNotNull(NotifStore.get(current.key)) // Only Android can remove it.
    }

    @Test fun playbackResumingBetweenGestureAndDispatchBlocksDismissal() {
        val token = session("resume").sessionToken
        val entry = post(player(delete = intent(33), token = token))
        val gateway = FakeGateway(entry, PlaybackState.STATE_PLAYING)
        assertFalse(MediaDismiss.dismiss(context, entry, gateway))
        assertTrue(gateway.sent.isEmpty())
        assertEquals(0, gateway.cancels)
        assertNotNull(NotifStore.get(entry.key))
    }

    @Test fun reusedNotificationKeyWithNewMediaSessionIsRejected() {
        val oldToken = session("old-session").sessionToken
        val newToken = session("new-session").sessionToken
        val old = post(player(delete = intent(34), token = oldToken))
        val now = post(player(delete = intent(35), token = newToken))
        val gateway = FakeGateway(now, PlaybackState.STATE_PAUSED)
        assertFalse(MediaDismiss.dismiss(context, old, gateway))
        assertTrue(gateway.sent.isEmpty())
        assertEquals(0, gateway.cancels)
    }

    @Test fun canceledDeleteIntentRestoresCardWithoutLocalNotificationRemoval() {
        val pending = intent(36)
        val token = session("canceled-action").sessionToken
        val entry = post(player(delete = pending, token = token))
        val gateway = FakeGateway(entry, PlaybackState.STATE_STOPPED)
        pending.cancel()
        assertFalse(MediaDismiss.dismiss(context, entry, gateway))
        assertEquals(listOf(pending), gateway.sent)
        assertEquals(0, gateway.cancels)
        assertNotNull(NotifStore.get(entry.key))
    }

    @Test fun disconnectedListenerOrDeletedNotificationCannotPretendSuccess() {
        val entry = post(player(delete = intent(37),
            token = session("missing-listener").sessionToken))
        val disconnected = FakeGateway(entry, PlaybackState.STATE_PAUSED, connected = false)
        assertFalse(MediaDismiss.dismiss(context, entry, disconnected))
        assertTrue(disconnected.sent.isEmpty())
        assertFalse(MediaDismiss.dismiss(context, entry,
            FakeGateway(null, PlaybackState.STATE_PAUSED)))
    }

    @Test fun clearableSessionlessPlayerKeepsNormalCancelPath() {
        val entry = post(player(clearable = true))
        assertEquals(Kind.MEDIA, entry.kind)
        val gateway = FakeGateway(entry)
        assertTrue(MediaDismiss.canSwipe(entry, null, context))
        assertTrue(MediaDismiss.dismiss(context, entry, gateway))
        assertEquals(1, gateway.cancels)
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun structuredSemanticsWorkWithUntranslatedActionTitles() {
        val pi = intent(38)
        // These published semantic codes are 13=Play and 14=Pause in API 37;
        // storing them numerically allows the app to read future/compat actions.
        val play = Notification.Action.Builder(null, "再生", pi).setSemanticAction(13).build()
        val pause = Notification.Action.Builder(null, "一時停止", pi).setSemanticAction(14).build()
        val entry = post(player(delete = pi, actions = arrayOf(play)))
        assertTrue(MediaDismiss.canSwipe(entry, null, context))
        val both = post(player(delete = pi, actions = arrayOf(play, pause)))
        assertFalse(MediaDismiss.canSwipe(both, null, context))
    }

    @Test fun iconResourcesProvideLocaleIndependentLegacySignal() {
        val pi = intent(39)
        val playIcon = Icon.createWithResource("android", android.R.drawable.ic_media_play)
        val pauseIcon = Icon.createWithResource("android", android.R.drawable.ic_media_pause)
        val play = Notification.Action.Builder(playIcon, "Wiedergabe", pi).build()
        val pause = Notification.Action.Builder(pauseIcon, "Pause starten", pi).build()
        assertTrue(MediaDismiss.canSwipe(post(player(delete = pi, actions = arrayOf(play))),
            null, context))
        assertFalse(MediaDismiss.canSwipe(post(player(delete = pi,
            actions = arrayOf(play, pause))), null, context))
    }

    @Test fun unknownLanguageAndMixedLegacyActionsFailClosed() {
        val pi = intent(40)
        val unknown = Notification.Action.Builder(null, "再生", pi).build()
        val play = Notification.Action.Builder(null, "Play", pi).build()
        val pause = Notification.Action.Builder(null, "Pause", pi).build()
        assertFalse(MediaDismiss.canSwipe(post(player(delete = pi,
            actions = arrayOf(unknown))), null, context))
        assertFalse(MediaDismiss.canSwipe(post(player(delete = pi,
            actions = arrayOf(play, pause))), null, context))
    }
}
