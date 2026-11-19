// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Currency for Search (ADR 0008): "100 usd", "$100", "€20", "rp 50.000", "100 usd to sgd". Rates
 * are the European Central Bank's daily reference rates, which Chronotif downloads only when this
 * is turned on; reading a query never needs them.
 */
object Money {
    /** [amount] of [from] asked for, in [to]; [to] is null when the query names no target. */
    data class Query(val amount: Double, val from: String, val to: String?)

    /** One day's rates: units of each currency per euro. */
    data class Rates(val date: LocalDate, val perEuro: Map<String, Double>) {
        fun has(code: String) = code == "EUR" || code in perEuro
        fun convert(amount: Double, from: String, to: String): Double? {
            val f = if (from == "EUR") 1.0 else perEuro[from] ?: return null
            val t = if (to == "EUR") 1.0 else perEuro[to] ?: return null
            return amount / f * t
        }
    }

    /** The currencies the ECB publishes, and the euro. */
    val CODES = setOf(
        "EUR", "USD", "JPY", "BGN", "CZK", "DKK", "GBP", "HUF", "PLN", "RON", "SEK", "CHF", "ISK", "NOK",
        "TRY", "AUD", "BRL", "CAD", "CNY", "HKD", "IDR", "ILS", "INR", "KRW", "MXN", "MYR", "NZD", "PHP",
        "SGD", "THB", "ZAR",
    )

    /** Symbols and names people type. "pound" stays a unit of mass; "£" and "gbp" are the currency. */
    private val NAMES: Map<String, String> = buildMap {
        for (c in CODES) put(c.lowercase(), c)
        putAll(
            mapOf(
                "$" to "USD", "us$" to "USD", "dollar" to "USD", "dollars" to "USD",
                "€" to "EUR", "euro" to "EUR", "euros" to "EUR",
                "£" to "GBP", "¥" to "JPY", "yen" to "JPY",
                "rp" to "IDR", "rupiah" to "IDR",
                "s$" to "SGD", "a$" to "AUD", "c$" to "CAD", "hk$" to "HKD", "nz$" to "NZD", "r$" to "BRL",
                "rm" to "MYR", "ringgit" to "MYR", "₹" to "INR", "rupee" to "INR", "rupees" to "INR",
                "₩" to "KRW", "won" to "KRW", "฿" to "THB", "baht" to "THB", "₱" to "PHP",
                "yuan" to "CNY", "rmb" to "CNY", "元" to "CNY", "₺" to "TRY", "₪" to "ILS",
            ),
        )
    }

    private val CONNECTORS = listOf(" to ", " in ", " into ", " as ", " = ", " -> ", " → ")

    fun code(name: String): String? = NAMES[name.trim().lowercase()]

    /** Reads a currency question, or null if [text] isn't one. */
    fun parse(text: String, numbers: Numbers): Query? {
        val q = " " + text.trim().lowercase().replace(Regex("\\s+"), " ") + " "
        var to: String? = null
        var left = q
        for (c in CONNECTORS) {
            val i = q.lastIndexOf(c)
            if (i < 0) continue
            val target = code(q.substring(i + c.length)) ?: continue
            to = target
            left = q.substring(0, i)
            break
        }
        val (amount, from) = amountAndCurrency(left.trim(), numbers) ?: run {
            // "usd to idr": one of it.
            val alone = code(left) ?: return null
            if (to == null) return null
            1.0 to alone
        }
        return Query(amount, from, to)
    }

    /** "100 usd", "100usd", "usd 100", "$100", "$ 100", "rp50.000". */
    private fun amountAndCurrency(s: String, numbers: Numbers): Pair<Double, String>? {
        val start = s.indexOfFirst { it.isDigit() }
        if (start < 0) return null
        var end = start
        while (end < s.length && numbers.isNumberChar(s[end])) end++
        // A trailing separator while typing ("100,") isn't part of the number.
        var numberEnd = end
        while (numberEnd > start && !s[numberEnd - 1].isDigit()) numberEnd--
        val before = s.substring(0, start).trim()
        val after = s.substring(end).trim()
        val from = when {
            before.isNotEmpty() && after.isEmpty() -> code(before)
            after.isNotEmpty() && before.isEmpty() -> code(after)
            else -> null
        } ?: return null
        val written = s.substring(start, numberEnd)
        // Rupiah and yen have no cents, so "rp 50.000" is fifty thousand in any language.
        val amount = if (fractionDigits(from) == 0 && WHOLE_GROUPED.matches(written)) {
            written.filter(Char::isDigit).toDoubleOrNull()
        } else {
            numbers.parse(written)
        } ?: return null
        return amount to from
    }

    private val WHOLE_GROUPED = Regex("\\d{1,3}([.,\u00A0\u202F]\\d{3})+")

    /** Currencies whose cents nobody uses, though ISO 4217 lists some (rupiah) with two. */
    private val WHOLE = setOf("IDR", "JPY", "KRW", "ISK", "HUF")

    /** The usual decimals: 2 for dollars and euros, none for rupiah, yen and won. */
    fun fractionDigits(code: String): Int = if (code in WHOLE) 0 else
        runCatching { java.util.Currency.getInstance(code).defaultFractionDigits }.getOrNull()?.takeIf { it >= 0 } ?: 2

    /** With no target named: into the local currency, or US dollars when the amount is already local. */
    fun target(query: Query, local: String): String = query.to ?: when {
        query.from != local -> local
        local != "USD" -> "USD"
        else -> "EUR"
    }

    private val ECB_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    /** The ECB publishes "around 16:00 CET" on working days; a little later to be sure it's out. */
    private val PUBLISHED_AT: LocalTime = LocalTime.of(16, 15)

    /**
     * The date of the newest rates the ECB should have published by [now]: today's after 16:15 in
     * Frankfurt, else the working day before. Weekends have none. Holidays aren't known, so on one
     * this names a day with no rates, and the download just brings the same ones back.
     */
    fun latestPublished(now: Instant): LocalDate {
        val t = now.atZone(ECB_ZONE)
        var d = t.toLocalDate()
        if (t.toLocalTime() < PUBLISHED_AT) d = d.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** Newer rates than [r] should be out. */
    fun isStale(r: Rates, now: Instant): Boolean = r.date < latestPublished(now)

    /** Parses the ECB's daily reference-rate XML (eurofxref-daily.xml). */
    fun parseEcb(xml: String): Rates? {
        val date = Regex("time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]").find(xml)?.groupValues?.get(1) ?: return null
        val rates = Regex("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]").findAll(xml)
            .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1] to it } }
            .toMap()
        if (rates.isEmpty()) return null
        return runCatching { Rates(LocalDate.parse(date), rates) }.getOrNull()
    }

    /** Stored form: "2026-11-19;USD=1.05,IDR=17000". */
    fun encode(r: Rates): String = r.date.toString() + ";" + r.perEuro.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun decode(s: String?): Rates? {
        if (s.isNullOrEmpty()) return null
        val date = runCatching { LocalDate.parse(s.substringBefore(';')) }.getOrNull() ?: return null
        val map = s.substringAfter(';', "").split(',').mapNotNull { p ->
            val k = p.substringBefore('=', "")
            p.substringAfter('=', "").toDoubleOrNull()?.takeIf { k.length == 3 }?.let { k to it }
        }.toMap()
        return if (map.isEmpty()) null else Rates(date, map)
    }
}
