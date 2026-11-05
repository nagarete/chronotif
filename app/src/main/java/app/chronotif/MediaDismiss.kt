// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import app.chronotif.core.Kind
import java.util.Locale

/**
 * Dismiss an inactive media notification through Android, never by hiding it locally.
 * A clearable notification uses the listener's normal cancellation path. A protected
 * notification needs an app-supplied deleteIntent and stronger evidence of inactivity.
 *
 * A delivered PendingIntent isn't proof of removal; NotifList restores the view if the
 * listener hasn't reported removal after the dismissal animation.
 */
object MediaDismiss {
    fun canSwipe(entry: Entry, playback: PlaybackState?, context: Context? = null): Boolean =
        entry.kind == Kind.MEDIA && MediaSwipePolicy.canAttempt(
            playback?.state,
            entry.clearable,
            entry.n.deleteIntent != null,
            MediaActionSignal.hasPlayWithoutPause(entry.n, context),
        )

    /** A small boundary for testing the *real* dismissal decision without a bound system service. */
    internal interface Gateway {
        fun current(key: String): Entry?
        fun playbackState(context: Context, token: MediaSession.Token): Int?
        fun listenerConnected(): Boolean
        fun cancel(entry: Entry)
        fun send(intent: PendingIntent)
    }

    private object AndroidGateway : Gateway {
        override fun current(key: String) = NotifStore.get(key)
        override fun playbackState(context: Context, token: MediaSession.Token): Int? =
            runCatching { MediaController(context.applicationContext, token).playbackState?.state }.getOrNull()
        override fun listenerConnected() = ListenerService.instance != null
        override fun cancel(entry: Entry) = Act.dismiss(entry)
        override fun send(intent: PendingIntent) = intent.send()
    }

    fun dismiss(context: Context, entry: Entry): Boolean = dismiss(context, entry, AndroidGateway)

    /**
     * Re-read both notification and playback state immediately before the dispatch.
     * Never send the old notification's deleteIntent after an app has replaced it.
     */
    internal fun dismiss(context: Context, entry: Entry, gateway: Gateway): Boolean {
        val live = gateway.current(entry.key) ?: return false
        if (live.kind != Kind.MEDIA || entry.kind != Kind.MEDIA) return false
        val token = mediaToken(live.n)
        if (mediaToken(entry.n) != token) return false
        val state = token?.let { gateway.playbackState(context, it) }
        if (!MediaSwipePolicy.canAttempt(
                state, live.clearable, live.n.deleteIntent != null,
                MediaActionSignal.hasPlayWithoutPause(live.n, context),
            )
        ) return false
        if (!gateway.listenerConnected()) return false

        return try {
            if (live.clearable) {
                gateway.cancel(live)
            } else {
                // Protected media can only be dismissed by the sending application.
                // Never call cancelNotification for FLAG_NO_CLEAR, or stop()/pause().
                gateway.send(live.n.deleteIntent ?: return false)
            }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    internal fun mediaToken(notification: Notification): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= 33) {
            notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }
}

/**
 * Preserve the original clearable-player gesture: only PLAYING/BUFFERING/CONNECTING
 * block it. Protected players require a real inactive state or a positive action hint.
 */
internal object MediaSwipePolicy {
    fun canAttempt(
        state: Int?, clearable: Boolean, hasDeleteIntent: Boolean,
        hasPlayAction: Boolean = false,
    ): Boolean {
        if (clearable) return state !in setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
        )
        if (!hasDeleteIntent) return false
        return when (state) {
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR -> true
            null, PlaybackState.STATE_NONE -> hasPlayAction
            else -> false
        }
    }
}

/**
 * Read media intent from app-supplied metadata, not from the phone's UI language.
 * Prefer structured semantic actions (PLAY=13 / PAUSE=14, introduced as public
 * constants in API 37; older platforms can still expose their integer values).
 * On older media notifications, look at the stable resource *entry name* of an
 * action icon. As a last-resort compatibility path, recognise a few exact legacy
 * labels; unknown languages never become evidence of inactivity by themselves.
 *
 * An unknown/both-Play-and-Pause signal fails closed for protected media.
 */
internal object MediaActionSignal {
    private const val SEMANTIC_PLAY = 13
    private const val SEMANTIC_PAUSE = 14

    fun hasPlayWithoutPause(notification: Notification, context: Context?): Boolean {
        var hasPlay = false
        var hasPause = false
        for (action in notification.actions.orEmpty()) {
            when (kind(action, context)) {
                Kind.PLAY -> hasPlay = true
                Kind.PAUSE -> hasPause = true
                Kind.UNKNOWN -> Unit
            }
        }
        return hasPlay && !hasPause
    }

    private enum class Kind { PLAY, PAUSE, UNKNOWN }

    private fun kind(action: Notification.Action, context: Context?): Kind {
        // getSemanticAction exists since API 28; these numerical values were
        // standardised for playback in Android 17 / API 37.
        when (action.semanticAction) {
            SEMANTIC_PLAY -> return Kind.PLAY
            SEMANTIC_PAUSE -> return Kind.PAUSE
        }
        val icon = action.getIcon()
        if (context != null && icon != null && icon.type == Icon.TYPE_RESOURCE) {
            val name = runCatching {
                val pkg = icon.resPackage.takeIf { it.isNotBlank() } ?: context.packageName
                val res = if (pkg == "android") Resources.getSystem()
                    else context.packageManager.getResourcesForApplication(pkg)
                res.getResourceEntryName(icon.resId).lowercase(Locale.ROOT)
            }.getOrNull()
            if (name != null) {
                val tokens = name.split(Regex("[^a-z0-9]+")).toSet()
                val play = "play" in tokens || "resume" in tokens
                val pause = "pause" in tokens
                if (play && !pause) return Kind.PLAY
                if (pause && !play) return Kind.PAUSE
                if (play && pause) return Kind.UNKNOWN
            }
        }
        return when (action.title.toString().trim().lowercase(Locale.ROOT)) {
            "play", "resume", "воспроизвести", "продолжить" -> Kind.PLAY
            "pause", "приостановить", "пауза" -> Kind.PAUSE
            else -> Kind.UNKNOWN
        }
    }
}
