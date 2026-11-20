// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * What's new after an update: the release notes this version has that the user hasn't been shown.
 * Notes are remembered one by one, so a nightly that adds a line to its version's notes shows just
 * that line, and a reworded line counts as new.
 */
object WhatsNew {
    class Release(val version: String, val headline: String?, val notes: List<String>)

    /** How a note is remembered once shown: short, and the same on every run. */
    fun key(note: String): String = note.trim().hashCode().toString(36)

    /**
     * The releases to show for an app at [current], newest first, each with only its unseen
     * notes; empty when there's nothing new. Releases newer than [current] (the website's notes
     * for a version not out yet) are left out. [seen] null means nothing was ever recorded, as
     * after updating from a version without What's new: only [current]'s own release shows.
     */
    fun unseen(releases: List<Release>, current: String, seen: Set<String>?): List<Release> {
        val mine = applicable(releases, current)
        if (seen == null) return mine.take(1).filter { it.notes.isNotEmpty() && sameVersion(it.version, current) }
        return mine.map { r -> Release(r.version, r.headline, r.notes.filter { key(it) !in seen }) }
            .filter { it.notes.isNotEmpty() }
    }

    /** Every note an app at [current] has, as keys: a fresh install has seen them all. */
    fun allKeys(releases: List<Release>, current: String): Set<String> =
        applicable(releases, current).flatMap { r -> r.notes.map(::key) }.toSet()

    private fun applicable(releases: List<Release>, current: String): List<Release> =
        releases.filter { Version.parse(it.version) != null && !Version.isNewer(base(it.version), base(current)) }
            .sortedWith { a, b ->
                when {
                    Version.isNewer(base(a.version), base(b.version)) -> -1
                    Version.isNewer(base(b.version), base(a.version)) -> 1
                    else -> 0
                }
            }

    private fun sameVersion(a: String, b: String) = Version.parse(a) == Version.parse(b)

    /** The numbered version only: "0.2.7-nightly.12" and "0.2.7-debug" are 0.2.7. */
    private fun base(v: String) = Version.parse(v)?.joinToString(".") ?: v
}
