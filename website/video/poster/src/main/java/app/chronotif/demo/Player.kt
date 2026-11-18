package app.chronotif.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * A pretend music player: a media session and its notification, with no audio.
 *   am start-foreground-service -n app.chronotif.demo.music/app.chronotif.demo.Player --es title ... --es artist ...
 */
class Player : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW))
        val title = intent?.getStringExtra("title") ?: "Slow Light"
        val artist = intent?.getStringExtra("artist") ?: "The Quiet Hours"
        val s = session ?: MediaSession(this, "demo").also { session = it }
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 214_000)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, Art.artwork())
            .build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(PlaybackState.STATE_PLAYING, intent?.getIntExtra("at", 62) ?.times(1000L) ?: 62_000L, 1f,
                SystemClock.elapsedRealtime())
            .build())
        // Buttons and the seek bar work, so a take can show them; there's still no audio.
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = state(s, PlaybackState.STATE_PLAYING)
            override fun onPause() = state(s, PlaybackState.STATE_PAUSED)
            override fun onSeekTo(pos: Long) = state(s, s.controller.playbackState?.state ?: PlaybackState.STATE_PLAYING, pos)
            override fun onSkipToNext() = state(s, PlaybackState.STATE_PLAYING, 0)
            override fun onSkipToPrevious() = state(s, PlaybackState.STATE_PLAYING, 0)
        })
        s.isActive = true
        val n = Post.builder(this, "playback")
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(Art.artwork())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
            .setOngoing(true)
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun state(s: MediaSession, state: Int, pos: Long? = null) {
        val old = s.controller.playbackState
        val now = SystemClock.elapsedRealtime()
        val at = pos ?: old?.let { o ->
            if (o.state == PlaybackState.STATE_PLAYING) o.position + (now - o.lastPositionUpdateTime) else o.position
        } ?: 0L
        s.setPlaybackState(PlaybackState.Builder(old).setState(state, at, 1f, now).build())
    }

    override fun onDestroy() {
        session?.release()
        super.onDestroy()
    }
}
