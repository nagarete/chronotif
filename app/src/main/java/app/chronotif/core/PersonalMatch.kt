// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * Search's rules for contacts and calendar events (ADR 0008). Both match a name or title from its
 * start or a later word's start: "bu" finds "Budi Santoso" and "Lunch with Budi", but not "Ibu".
 */
object PersonalMatch {
    /** Contacts shown before "Show more". */
    const val CONTACTS_SHOWN = 3
    /** Contacts shown at most, after "Show more". */
    const val CONTACTS_MAX = 20
    const val EVENTS_SHOWN = 3
    const val EVENTS_PAST_DAYS = 30L
    const val EVENTS_AHEAD_DAYS = 365L

    /** Typed digits at least this long also match phone numbers. */
    private const val MIN_NUMBER_DIGITS = 3

    private fun wordStart(name: String, query: String): Int? = AppMatch.tier(name, query)?.takeIf { it < 2 }

    class Number(val number: String, val isDefault: Boolean, val isMobile: Boolean)

    /** The number the call and message buttons use: the one set as default, else a mobile, else the first. */
    fun callNumber(numbers: List<Number>): String? =
        (numbers.firstOrNull { it.isDefault } ?: numbers.firstOrNull { it.isMobile } ?: numbers.firstOrNull())?.number

    /**
     * Contacts whose name matches, best tier first and alphabetical within it. A query of digits
     * also finds contacts whose number contains them, after the name matches.
     */
    fun <T> contacts(all: List<T>, query: String, name: (T) -> String, numbers: (T) -> List<String>): List<T> {
        val byName = all.mapNotNull { c -> wordStart(name(c), query)?.let { Triple(it, AppMatch.fold(name(c)), c) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
        val digits = query.filter(Char::isDigit)
        val numberOnly = query.all { it.isDigit() || it in "+ -()" }
        if (!numberOnly || digits.length < MIN_NUMBER_DIGITS) return byName
        val named = byName.toHashSet()
        val byNumber = all.filter { c -> c !in named && numbers(c).any { it.filter(Char::isDigit).contains(digits) } }
            .sortedBy { AppMatch.fold(name(it)) }
        return byName + byNumber
    }

    /**
     * Events whose title matches, soonest first: those not over yet by start time, then past ones,
     * the most recent first. A repeating event shows once, at its next (or last) time, and the
     * same event in two calendars (a holiday in two holiday calendars) shows once.
     */
    fun <T> events(all: List<T>, query: String, now: Long, id: (T) -> Long, title: (T) -> String, begin: (T) -> Long, end: (T) -> Long): List<T> {
        val matching = all.filter { wordStart(title(it), query) != null }
        val (upcoming, past) = matching.partition { end(it) > now }
        val chosen = LinkedHashMap<Long, T>()
        val seen = HashSet<Pair<String, Long>>()
        for (e in upcoming.sortedBy(begin) + past.sortedByDescending(begin)) {
            if (id(e) in chosen || !seen.add(AppMatch.fold(title(e)) to begin(e))) continue
            chosen[id(e)] = e
        }
        return chosen.values.take(EVENTS_SHOWN)
    }
}
