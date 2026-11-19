// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import kotlin.math.pow

/**
 * Quick math for Search (ADR 0008): + − × ÷ (also * / x), ^ for powers, % for percent, and
 * brackets. Numbers follow the phone's language ([Numbers]). Only an expression with at least one
 * operation is math: a lone "50,000" isn't answered.
 */
object Calc {
    /** The value of [text], or null if it isn't a complete expression with an operation. */
    fun evaluate(text: String, numbers: Numbers): Double? {
        val tokens = tokenize(text.trim(), numbers) ?: return null
        // "-5" is a number, not a sum: there has to be an operation between two things, or a percent.
        val operates = tokens.withIndex().any { (i, tok) ->
            tok == Tok.Percent || (tok is Tok.Op && i > 0 && tokens[i - 1].let { it is Tok.Num || it == Tok.Close || it == Tok.Percent })
        }
        if (!operates) return null
        val p = Parser(tokens)
        val v = p.expression() ?: return null
        if (!p.done || v.isNaN() || v.isInfinite()) return null
        return v
    }

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Op(val c: Char) : Tok
        data object Open : Tok
        data object Close : Tok
        data object Percent : Tok
    }

    private fun tokenize(s: String, numbers: Numbers): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() && !numbers.isNumberChar(c) -> i++
                c.isDigit() || (numbers.isNumberChar(c) && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && numbers.isNumberChar(s[i])) i++
                    // A trailing separator ("12," while typing) isn't part of the number.
                    var end = i
                    while (end > start && !s[end - 1].isDigit()) end--
                    out += Tok.Num(numbers.parse(s.substring(start, end)) ?: return null)
                    if (end < i) return null
                }
                c == '+' -> { out += Tok.Op('+'); i++ }
                c == '-' || c == '−' || c == '–' -> { out += Tok.Op('-'); i++ }
                c == '*' || c == '×' || c == '·' || ((c == 'x' || c == 'X') && out.lastOrNull().let { it is Tok.Num || it == Tok.Close || it == Tok.Percent }) ->
                    { out += Tok.Op('*'); i++ }
                c == '/' || c == '÷' -> { out += Tok.Op('/'); i++ }
                c == '^' -> { out += Tok.Op('^'); i++ }
                c == '%' -> { out += Tok.Percent; i++ }
                c == '(' -> { out += Tok.Open; i++ }
                c == ')' -> { out += Tok.Close; i++ }
                else -> return null
            }
        }
        return out
    }

    /** expression := term (('+' | '-') term)*, where "+ n%" adds n percent of what's before it. */
    private class Parser(private val t: List<Tok>) {
        private var i = 0
        val done get() = i == t.size

        private fun peek() = t.getOrNull(i)

        fun expression(): Double? {
            var v = term() ?: return null
            while (true) {
                val op = (peek() as? Tok.Op)?.c?.takeIf { it == '+' || it == '-' } ?: return v
                i++
                val start = i
                val r = term() ?: return null
                // 200 + 15% is 230, as on a calculator: the percent is of the left side.
                val isPercentOfLeft = i - start == 2 && t[start] is Tok.Num && t[start + 1] == Tok.Percent
                val rhs = if (isPercentOfLeft) v * r else r
                v = if (op == '+') v + rhs else v - rhs
            }
        }

        private fun term(): Double? {
            var v = power() ?: return null
            while (true) {
                val op = (peek() as? Tok.Op)?.c?.takeIf { it == '*' || it == '/' } ?: return v
                i++
                val r = power() ?: return null
                v = if (op == '*') v * r else v / r
            }
        }

        private fun power(): Double? {
            val base = unary() ?: return null
            if ((peek() as? Tok.Op)?.c != '^') return base
            i++
            val exp = power() ?: return null // right-associative
            return base.pow(exp)
        }

        private fun unary(): Double? {
            val op = (peek() as? Tok.Op)?.c
            if (op == '-' || op == '+') {
                i++
                val v = unary() ?: return null
                return if (op == '-') -v else v
            }
            return postfix()
        }

        private fun postfix(): Double? {
            var v = primary() ?: return null
            while (peek() == Tok.Percent) {
                i++
                v /= 100
            }
            return v
        }

        private fun primary(): Double? = when (val tok = peek()) {
            is Tok.Num -> { i++; tok.v }
            Tok.Open -> {
                i++
                val v = expression()
                if (v == null || peek() != Tok.Close) null else { i++; v }
            }
            else -> null
        }
    }
}
