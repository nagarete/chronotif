// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * Numbers in Search's answers, read and written the way the phone's language does (ADR 0005, 0008):
 * in English 50,000 is fifty thousand and 1.5 is one and a half; in Indonesian those are 50.000 and 1,5.
 */
class Numbers(val locale: Locale) {
    private val symbols = DecimalFormatSymbols.getInstance(locale)
    val group: Char = symbols.groupingSeparator
    val decimal: Char = symbols.decimalSeparator

    /**
     * Reads one number written with this language's separators. A grouping separator must be
     * followed by three digits; a lone separator that isn't ("1.5" in Indonesian) is taken as the
     * decimal point, which is what the person meant. Returns null if it isn't a number.
     */
    fun parse(text: String): Double? {
        val s = text.replace(NBSP, group).replace(NNBSP, group)
        if (s.isEmpty() || s.any { !it.isDigit() && it != group && it != decimal }) return null
        if (!s.any(Char::isDigit)) return null
        val decimals = s.count { it == decimal }
        if (decimals > 1) return null
        val whole = s.substringBefore(decimal)
        val fraction = if (decimals == 1) s.substringAfter(decimal) else ""
        if (fraction.contains(group)) return null
        val groups = whole.split(group)
        val plain = when {
            groups.size == 1 -> whole
            groups.drop(1).all { it.length == 3 && it.all(Char::isDigit) } && groups[0].isNotEmpty() -> groups.joinToString("")
            // One separator that can't be grouping, and no decimal point: it's the decimal point.
            groups.size == 2 && decimals == 0 -> return "${groups[0].ifEmpty { "0" }}.${groups[1]}".toDoubleOrNull()
            else -> return null
        }
        return "${plain.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}".toDoubleOrNull()
    }

    /** True for the characters a number can be written with here. */
    fun isNumberChar(c: Char) = c.isDigit() || c == group || c == decimal || c == NBSP || c == NNBSP

    /**
     * Rounds to [significant] digits, drops trailing zeros, and groups the thousands. Values too
     * large or small to read in full use the scientific form.
     */
    fun format(value: Double, significant: Int, maxFraction: Int = 20): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(significant, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val magnitude = Math.abs(value)
        if (magnitude >= 1e15 || magnitude < 1e-6) {
            return DecimalFormat("0.${"#".repeat(significant - 1)}E0", symbols).format(rounded)
        }
        val scale = rounded.scale().coerceIn(0, maxFraction)
        return (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            isGroupingUsed = true
            minimumFractionDigits = 0
            maximumFractionDigits = scale
            roundingMode = RoundingMode.HALF_EVEN
        }.format(rounded)
    }

    /** The same number for pasting: no grouping, this language's decimal point. */
    fun plain(value: Double, significant: Int, maxFraction: Int = 20): String =
        format(value, significant, maxFraction).filter { it != group && it != NBSP && it != NNBSP }

    private companion object {
        /** French and others group with a (narrow) no-break space. */
        const val NBSP = ' '
        const val NNBSP = ' '
    }
}
