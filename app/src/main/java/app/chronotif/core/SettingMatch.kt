// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * How Settings' own search matches a typed query against settings. Each setting has a title and
 * other words: its section, its group, its description and a few words people type for it. A
 * setting whose title matches ranks above one found only by its other words; ties keep the order
 * settings appear in.
 */
object SettingMatch {
    /** A setting's words, folded once so matching on each keystroke stays cheap. */
    class Doc(title: String, other: String) {
        val title = AppMatch.fold(title)
        val titleWords = title.split(WORD_BREAK).filter { it.isNotEmpty() }.map { AppMatch.fold(it) }
        val words = (this.title + " " + AppMatch.fold(other)).split(WORD_BREAK).filter { it.isNotEmpty() }
    }

    /** A typed query, folded once. */
    class Query(typed: String) {
        val text = AppMatch.fold(typed)
        val words = text.split(WORD_BREAK).filter { it.isNotEmpty() }
    }

    /**
     * 0: the title starts with the query. 1: a later word of the title does ("col" → "Usual
     * colour"). 2: each typed word starts one of the setting's words, in any order ("clock size",
     * "dismiss"). 3: the title has it anywhere. null: no match.
     */
    fun tier(doc: Doc, q: Query): Int? {
        if (q.words.isEmpty()) return null
        return when {
            doc.title.startsWith(q.text) -> 0
            doc.titleWords.drop(1).any { it.startsWith(q.text) } -> 1
            q.words.all { w -> doc.words.any { it.startsWith(w) } } -> 2
            doc.title.contains(q.text) -> 3
            else -> null
        }
    }

    /** The settings matching [typed], best tier first and in their own order within a tier, at most [limit]. */
    fun <T> search(items: List<T>, typed: String, limit: Int, doc: (T) -> Doc): List<T> {
        val q = Query(typed)
        if (q.words.isEmpty()) return emptyList()
        return items.withIndex()
            .mapNotNull { (i, item) -> tier(doc(item), q)?.let { Triple(it, i, item) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }
    }

    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")
}
