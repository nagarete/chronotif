// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** How long a notification is snoozed for, as the Preview offers it (ADR 0010). */
enum class SnoozeFor { MINUTES_15, HOUR_1, HOURS_3, TOMORROW_MORNING }

object Snooze {
    const val MORNING_HOUR = 8
    /** Before this hour it's still night: "tomorrow morning" is the morning about to come. */
    const val NIGHT_ENDS_HOUR = 4

    /** When a notification snoozed at [now] for [choice] comes back. */
    fun until(choice: SnoozeFor, now: Long, zone: ZoneId): Long = when (choice) {
        SnoozeFor.MINUTES_15 -> now + 15 * 60_000L
        SnoozeFor.HOUR_1 -> now + 60 * 60_000L
        SnoozeFor.HOURS_3 -> now + 3 * 60 * 60_000L
        SnoozeFor.TOMORROW_MORNING -> {
            val t = Instant.ofEpochMilli(now).atZone(zone)
            val day = if (t.hour < NIGHT_ENDS_HOUR) t.toLocalDate() else t.toLocalDate().plusDays(1)
            day.atTime(MORNING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** A picked clock time: the next time it comes round, today or else tomorrow. */
    fun at(time: LocalTime, now: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val today = t.toLocalDate().atTime(time).atZone(zone)
        return (if (today.toInstant().toEpochMilli() > now) today else t.toLocalDate().plusDays(1).atTime(time).atZone(zone))
            .toInstant().toEpochMilli()
    }

    /** What the time picker starts at: an hour from now, rounded up to five minutes. */
    fun suggested(now: Long, zone: ZoneId): LocalTime {
        val t = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().plusHours(1).withSecond(0).withNano(0)
        val up = (5 - t.minute % 5) % 5
        return t.plusMinutes(up.toLong())
    }
}
