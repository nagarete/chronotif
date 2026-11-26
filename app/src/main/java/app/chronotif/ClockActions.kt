// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.chronotif.core.ClockCommand

/**
 * Sets a timer or alarm typed into Search (ADR 0023) in the phone's clock app. Skip-UI asks the
 * clock app to do it without opening, so it works on the lock screen too; a clock app that opens
 * anyway shows once the phone is unlocked.
 */
object ClockActions {
    fun set(context: Context, command: ClockCommand): Boolean {
        val intent = when (command) {
            is ClockCommand.Timer -> Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
            is ClockCommand.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
        }.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** When an alarm at [hour]:[minute] next rings: today if that's still ahead, otherwise tomorrow. */
    fun isToday(hour: Int, minute: Int, now: java.time.LocalTime = java.time.LocalTime.now()): Boolean =
        java.time.LocalTime.of(hour, minute).isAfter(now)
}
