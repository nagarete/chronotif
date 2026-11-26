// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/** When Settings shows the Support sheet. */
object SupportAsk {
    private const val DAY = 86_400_000L
    const val WAIT = 7 * DAY
    /** Someone who tapped Support, on the sheet or the Ko-fi button, has been asked enough to see it less often. */
    const val WAIT_TAPPED = 21 * DAY

    /**
     * Whether the sheet is due at [now]. [since] is when this install first ran a version with
     * Support, so an update gets the same quiet first week as a new install. [lastShown] is 0
     * until the sheet has shown. Weeks without opening Settings still add up to one sheet.
     */
    fun due(now: Long, since: Long, lastShown: Long, tapped: Boolean): Boolean {
        if (since <= 0L) return false
        val from = if (lastShown > 0L) lastShown else since
        return now - from >= if (tapped) WAIT_TAPPED else WAIT
    }
}
