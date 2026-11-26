// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * A phone number typed into Search (ADR 0023), so it can be called, messaged or opened in
 * WhatsApp without being saved first.
 */
object TypedNumber {
    /** Fewer digits is a short code or a sum; more than E.164's 15 isn't a phone number. */
    const val MIN_DIGITS = 7
    const val MAX_DIGITS = 15

    /**
     * [query] as a dialable number ("+62 812-3456-7890" → "+6281234567890"), or null when it isn't
     * one. Separated groups count only with a leading + or 0, the way numbers are written, so
     * "12-3456789" stays a sum.
     */
    fun of(query: String): String? {
        val q = query.trim()
        if (!SHAPE.matches(q)) return null
        val digits = q.filter(Char::isDigit)
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null
        val separated = q.any { it in SEPARATORS }
        if (separated && !(q.startsWith('+') || q.startsWith('0'))) return null
        return (if (q.startsWith('+')) "+" else "") + digits
    }

    private const val SEPARATORS = " -(). "
    private val SHAPE = Regex("\\+?[\\d \\-().\\u00A0]+")
}
