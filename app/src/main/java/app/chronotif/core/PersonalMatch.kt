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

    private fun wordStart(key: AppMatch.Key, foldedQuery: String): Int? = AppMatch.tier(key, foldedQuery)?.takeIf { it < 2 }

    class Number(val number: String, val isDefault: Boolean, val isMobile: Boolean)

    /** The number the call and message buttons use: the one set as default, else a mobile, else the first. */
    fun callNumber(numbers: List<Number>): String? =
        (numbers.firstOrNull { it.isDefault } ?: numbers.firstOrNull { it.isMobile } ?: numbers.firstOrNull())?.number

    /**
     * Contacts whose name matches, best tier first and alphabetical within it. A query of digits
     * also finds contacts whose number contains them, after the name matches.
     */
    fun <T> contacts(all: List<T>, query: String, name: (T) -> String, numbers: (T) -> List<String>): List<T> =
        rankContacts(all, query, { AppMatch.Key(name(it)) }, { c -> numbers(c).map { n -> n.filter(Char::isDigit) } })

    /** [contacts] for names folded ahead with [AppMatch.Key] and numbers kept as their digits only. */
    fun <T> rankContacts(all: List<T>, query: String, key: (T) -> AppMatch.Key, digits: (T) -> List<String>): List<T> {
        val q = AppMatch.fold(query)
        val byName = all.mapNotNull { c -> val k = key(c); wordStart(k, q)?.let { Triple(it, k.folded, c) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
        val typed = query.filter(Char::isDigit)
        val numberOnly = query.all { it.isDigit() || it in "+ -()" }
        if (!numberOnly || typed.length < MIN_NUMBER_DIGITS) return byName
        val named = byName.toHashSet()
        val byNumber = all.filter { c -> c !in named && digits(c).any { it.contains(typed) } }
            .sortedBy { key(it).folded }
        return byName + byNumber
    }

    /**
     * Events whose title matches, soonest first: those not over yet by start time, then past ones,
     * the most recent first. A repeating event shows once, at its next (or last) time, and the
     * same event in two calendars (a holiday in two holiday calendars) shows once.
     */
    fun <T> events(all: List<T>, query: String, now: Long, id: (T) -> Long, title: (T) -> String, begin: (T) -> Long, end: (T) -> Long): List<T> {
        val keys = all.associateWith { AppMatch.Key(title(it)) }
        return rankEvents(all, query, now, id, { keys.getValue(it) }, begin, end)
    }

    /** [events] for titles folded ahead with [AppMatch.Key]. */
    fun <T> rankEvents(all: List<T>, query: String, now: Long, id: (T) -> Long, key: (T) -> AppMatch.Key, begin: (T) -> Long, end: (T) -> Long): List<T> {
        val q = AppMatch.fold(query)
        val matching = all.filter { wordStart(key(it), q) != null }
        val (upcoming, past) = matching.partition { end(it) > now }
        val chosen = LinkedHashMap<Long, T>()
        val seen = HashSet<Pair<String, Long>>()
        for (e in upcoming.sortedBy(begin) + past.sortedByDescending(begin)) {
            if (id(e) in chosen || !seen.add(key(e).folded to begin(e))) continue
            chosen[id(e)] = e
        }
        return chosen.values.take(EVENTS_SHOWN)
    }
}
