// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * Which parts of the panel header's date show. The settings choose the parts; the locale
 * decides their order, punctuation and spelling (ADR 0005).
 */
object HeaderDate {
    /**
     * The skeleton for the chosen parts, for `DateFormat.getBestDateTimePattern`. The day and
     * month always show: a date without its month is ambiguous.
     */
    fun skeleton(weekday: Boolean, year: Boolean, shortNames: Boolean): String = buildString {
        if (weekday) append(if (shortNames) "EEE" else "EEEE")
        append("d")
        append(if (shortNames) "MMM" else "MMMM")
        if (year) append("y")
    }
}
