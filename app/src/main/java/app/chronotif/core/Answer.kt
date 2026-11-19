// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/** Search's answer card (ADR 0008): at most one answer per query, checked in this order. */
sealed interface Answer {
    data class Math(val value: Double) : Answer
    data class Converted(val conversion: Units.Conversion) : Answer
    data class Time(val place: WorldClock.Place) : Answer

    companion object {
        fun of(query: String, numbers: Numbers): Answer? {
            if (query.isBlank()) return null
            WorldClock.place(query)?.let { return Time(it) }
            Units.convert(query, numbers)?.let { return Converted(it) }
            Calc.evaluate(query, numbers)?.let { return Math(it) }
            return null
        }

        /** Math shows up to 10 significant digits; conversions 6, which is more than any of them is accurate to. */
        const val MATH_DIGITS = 10
        const val UNIT_DIGITS = 6

        /** "2 h ahead" etc.: how far [zoneOffsetSeconds] is from [hereOffsetSeconds], in minutes. */
        fun minutesAhead(hereOffsetSeconds: Int, zoneOffsetSeconds: Int): Int = (zoneOffsetSeconds - hereOffsetSeconds) / 60
    }
}
