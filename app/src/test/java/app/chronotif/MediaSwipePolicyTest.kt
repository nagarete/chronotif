// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.media.session.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSwipePolicyTest {
    @Test
    fun knownInactiveMediaNeedsARealDismissalPath() {
        for (state in listOf(
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR,
        )) {
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = true, hasDeleteIntent = false))
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = false, hasDeleteIntent = true))
            assertFalse(MediaSwipePolicy.canAttempt(state, clearable = false, hasDeleteIntent = false))
        }
    }

    @Test
    fun missingSessionNeedsAnUnambiguousPlayActionAndDismissalPath() {
        for (state in listOf(null, PlaybackState.STATE_NONE)) {
            assertFalse(MediaSwipePolicy.canAttempt(state, clearable = false, hasDeleteIntent = true))
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = false,
                hasDeleteIntent = true, hasPlayAction = true))
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = true,
                hasDeleteIntent = false, hasPlayAction = true))
            // Existing behaviour: don't require a Play action on clearable media.
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = true,
                hasDeleteIntent = false, hasPlayAction = false))
            assertFalse(MediaSwipePolicy.canAttempt(state, clearable = false,
                hasDeleteIntent = false, hasPlayAction = true))
        }
    }

    @Test
    fun clearablePlayersKeepTheOriginalPlayingOnlyBlocklist() {
        // Upstream's original playing() considers only these three states active.
        for (state in listOf(
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
        )) {
            assertTrue(MediaSwipePolicy.canAttempt(state, clearable = true,
                hasDeleteIntent = false, hasPlayAction = false))
            assertFalse(MediaSwipePolicy.canAttempt(state, clearable = false,
                hasDeleteIntent = true, hasPlayAction = true))
        }
    }

    @Test
    fun activeAndTransitionalSessionsCannotBeDismissedEvenWithAPlayAction() {
        for (state in listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        )) {
            if (state in listOf(PlaybackState.STATE_PLAYING,
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)) {
                assertFalse(MediaSwipePolicy.canAttempt(state, clearable = true,
                    hasDeleteIntent = true, hasPlayAction = true))
            }
            assertFalse(MediaSwipePolicy.canAttempt(state, clearable = false,
                hasDeleteIntent = true, hasPlayAction = true))
        }
    }
}
