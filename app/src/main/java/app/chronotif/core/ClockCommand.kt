// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * A timer or an alarm typed into Search (ADR 0023), in English or Indonesian: `timer 10 min`,
 * `1h30 timer`, `timer 1 jam 30 menit`, `alarm 6:30`, `alarm 6.30 pm`, `alarm jam 7 malam`.
 * Search shows it as its answer, and only a tap or Go sets it.
 */
sealed interface ClockCommand {
    data class Timer(val seconds: Int) : ClockCommand
    /** [hour] 0–23. A bare `6:30` is always 06:30; the evening is `6:30 pm` or `18:30`. */
    data class Alarm(val hour: Int, val minute: Int) : ClockCommand

    companion object {
        /** Android's timer intent takes at most a day. */
        const val MAX_TIMER_SECONDS = 24 * 60 * 60

        fun parse(query: String): ClockCommand? {
            val words = AppMatch.fold(query).replace(SPLIT_UNITS, " ").split(SPACE).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            val timer = words.indexOfFirst { it in TIMER_WORDS }
            if (timer >= 0) return timer(words.filterIndexed { i, w -> i != timer && w !in TIMER_FILLER })
            return alarm(alarmTime(words)?.filter { it !in FILLER } ?: return null)
        }

        /** The words holding the time: around "alarm" or "bangunkan", or after "wake me (up)". */
        private fun alarmTime(words: List<String>): List<String>? {
            val at = words.indexOfFirst { it in ALARM_WORDS }
            if (at >= 0) return if (at == words.lastIndex) words.take(at) else words.drop(at + 1)
            if (words.size >= 2 && words[0] == "wake" && words[1] == "me") return words.drop(if (words.getOrNull(2) == "up") 3 else 2)
            return null
        }

        private fun timer(words: List<String>): Timer? {
            if (words.isEmpty()) return null
            // A bare number is minutes: "timer 10".
            if (words.size == 1) number(words[0])?.let { return timerOf(it * 60) }
            var total = 0.0
            var i = 0
            var lastUnit = 0
            while (i < words.size) {
                val n = number(words[i]) ?: return null
                val unit = words.getOrNull(i + 1)?.let(::unitSeconds)
                if (unit != null) {
                    total += n * unit
                    lastUnit = unit
                    i += 2
                } else if (i == words.size - 1 && lastUnit > 1) {
                    // "1h30": the trailing number is the next unit down.
                    total += n * (lastUnit / 60)
                    i++
                } else return null
            }
            return timerOf(total)
        }

        private fun timerOf(seconds: Double): Timer? {
            val s = Math.round(seconds).toInt()
            return if (s in 1..MAX_TIMER_SECONDS) Timer(s) else null
        }

        private fun alarm(words: List<String>): Alarm? {
            if (words.isEmpty() || words.size > 2) return null
            val (h, m) = time(words[0]) ?: return null
            val part = words.getOrNull(1)
            val hour = when (part) {
                null -> h
                "am", "a.m.", "pagi" -> if (h > 12) return null else if (h == 12) 0 else h
                "pm", "p.m." -> if (h > 12) return null else if (h == 12) 12 else h + 12
                // Indonesian parts of the day: siang is midday, sore the afternoon, malam the evening and night.
                "siang" -> if (h > 12) return null else if (h <= 10) h + 12 else h
                "sore" -> if (h > 12) return null else if (h == 12) 12 else h + 12
                "malam" -> if (h > 12) return null else if (h == 12) 0 else if (h >= 6) h + 12 else h
                else -> return null
            }
            return Alarm(hour, m)
        }

        /** "6", "6:30", "6.30" or "06.30" as hour and minute. */
        private fun time(w: String): Pair<Int, Int>? {
            val match = TIME.matchEntire(w) ?: return null
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return if (h in 0..23 && m in 0..59) h to m else null
        }

        private fun number(w: String): Double? =
            if (NUMBER.matches(w)) w.replace(',', '.').toDoubleOrNull() else null

        private fun unitSeconds(w: String): Int? = when (w) {
            "h", "hr", "hrs", "hour", "hours", "jam" -> 3600
            "m", "min", "mins", "minute", "minutes", "menit", "mnt" -> 60
            "s", "sec", "secs", "second", "seconds", "detik", "dtk" -> 1
            else -> null
        }

        private val SPACE = Regex("\\s+")
        /** "1h30m" and "10min" read as "1 h 30 m" and "10 min". */
        private val SPLIT_UNITS = Regex("(?<=\\d)(?=[a-z])|(?<=[a-z])(?=\\d)")
        private val NUMBER = Regex("\\d+([.,]\\d+)?")
        private val TIME = Regex("(\\d{1,2})(?:[:.](\\d{2}))?")
        private val TIMER_WORDS = setOf("timer")
        private val ALARM_WORDS = setOf("alarm", "bangunkan", "bangunin")
        private val TIMER_FILLER = setOf("set", "start", "a", "an", "for", "pasang", "atur", "untuk", "selama")
        /** "jam" is a unit in a timer but "o'clock" before an alarm's time: `alarm jam 6`. */
        private val FILLER = setOf("set", "a", "an", "for", "at", "pasang", "atur", "untuk", "jam", "pukul", "saya", "aku")
    }
}
