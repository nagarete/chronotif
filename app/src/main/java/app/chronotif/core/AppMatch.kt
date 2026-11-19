// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.text.Normalizer

/**
 * Search's pure rules (ADR 0008): how a typed query matches app names, and which apps the
 * suggestion row shows. Apps aren't events, so results rank by how well the name matches, not by time.
 */
object AppMatch {
    const val SUGGESTIONS = 4

    /** Case- and accent-insensitive form of [s], so "cafe" finds "Café". */
    fun fold(s: CharSequence): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase()
            .trim()

    /**
     * 0: the name starts with the query. 1: a later word starts with it ("music" → "YouTube
     * Music"). 2: it appears anywhere ("tok" → "TikTok"). null: no match.
     */
    fun tier(name: String, query: String): Int? {
        val n = fold(name)
        val q = fold(query)
        if (q.isEmpty()) return null
        return when {
            n.startsWith(q) -> 0
            n.split(WORD_BREAK).drop(1).any { it.startsWith(q) } -> 1
            n.contains(q) -> 2
            else -> null
        }
    }

    /** The apps matching [query], best tier first, alphabetical within a tier. */
    fun <T> search(apps: List<T>, query: String, name: (T) -> String): List<T> =
        apps.mapNotNull { a -> tier(name(a), query)?.let { t -> Triple(t, fold(name(a)), a) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }

    /**
     * The suggestion row: the apps that notified most recently, newest first, once each, from a
     * record of each app's last notification time. Apps no longer launchable are skipped.
     */
    fun <K> suggestions(lastNotified: Map<K, Long>, launchable: (K) -> Boolean, limit: Int = SUGGESTIONS): List<K> =
        lastNotified.entries.sortedByDescending { it.value }
            .map { it.key }
            .filter(launchable)
            .take(limit)

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
}
