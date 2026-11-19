// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class MoneyTest {
    private val en = Numbers(Locale.US)
    private val id = Numbers(Locale.forLanguageTag("id-ID"))

    private fun q(text: String, numbers: Numbers = en) = Money.parse(text, numbers)

    @Test fun readsCodesSymbolsAndNames() {
        assertEquals(Money.Query(100.0, "USD", null), q("100 usd"))
        assertEquals(Money.Query(100.0, "USD", null), q("100usd"))
        assertEquals(Money.Query(100.0, "USD", null), q("usd 100"))
        assertEquals(Money.Query(100.0, "USD", null), q("$100"))
        assertEquals(Money.Query(20.0, "EUR", null), q("€20"))
        assertEquals(Money.Query(5.0, "JPY", null), q("¥5"))
        assertEquals(Money.Query(12.5, "SGD", null), q("s$12.50"))
        assertEquals(Money.Query(3.0, "USD", null), q("3 dollars"))
    }

    @Test fun readsATarget() {
        assertEquals(Money.Query(100.0, "USD", "SGD"), q("100 usd to sgd"))
        assertEquals(Money.Query(100.0, "USD", "SGD"), q("100 USD in SGD"))
        assertEquals(Money.Query(1.0, "USD", "IDR"), q("usd to idr"))
    }

    @Test fun rupiahThousandsReadTheSameInAnyLanguage() {
        assertEquals(Money.Query(50000.0, "IDR", null), q("rp 50.000"))
        assertEquals(Money.Query(50000.0, "IDR", null), q("rp 50,000"))
        assertEquals(Money.Query(50000.0, "IDR", null), q("rp50.000", id))
        assertEquals(Money.Query(1500000.0, "IDR", "USD"), q("rp 1.500.000 to usd"))
    }

    @Test fun dollarDecimalsFollowTheLanguage() {
        assertEquals(Money.Query(1.5, "USD", null), q("$1.50"))
        assertEquals(Money.Query(1.5, "USD", null), q("$1,50", id))
    }

    @Test fun notCurrency() {
        assertNull(q("100"))
        assertNull(q("usd"))
        assertNull(q("5 pounds"))
        assertNull(q("5 km in mi"))
        assertNull(q("100 usd to banana").takeIf { it?.to != null })
    }

    @Test fun targetIsLocalOrDollarsWhenAlreadyLocal() {
        assertEquals("IDR", Money.target(Money.Query(1.0, "USD", null), "IDR"))
        assertEquals("USD", Money.target(Money.Query(1.0, "IDR", null), "IDR"))
        assertEquals("EUR", Money.target(Money.Query(1.0, "USD", null), "USD"))
        assertEquals("SGD", Money.target(Money.Query(1.0, "USD", "SGD"), "IDR"))
    }

    private val ecb = """
        <gesmes:Envelope><Cube><Cube time='2026-11-18'>
        <Cube currency='USD' rate='1.0500'/>
        <Cube currency='JPY' rate='160.00'/>
        <Cube currency='IDR' rate='17325'/>
        <Cube currency='SGD' rate='1.4000'/>
        </Cube></Cube></gesmes:Envelope>
    """.trimIndent()

    @Test fun parsesTheEcbFileAndConvertsThroughTheEuro() {
        val r = Money.parseEcb(ecb)!!
        assertEquals(LocalDate.of(2026, 11, 18), r.date)
        assertEquals(1.05, r.perEuro["USD"]!!, 0.0)
        assertEquals(16500.0, r.convert(1.0, "USD", "IDR")!!, 0.01)
        assertEquals(1.0 / 1.05, r.convert(1.0, "USD", "EUR")!!, 1e-9)
        assertNull(r.convert(1.0, "USD", "XYZ"))
        assertEquals(r, Money.decode(Money.encode(r)))
    }

    @Test fun usualDecimals() {
        assertEquals(0, Money.fractionDigits("IDR"))
        assertEquals(0, Money.fractionDigits("JPY"))
        assertEquals(2, Money.fractionDigits("USD"))
        assertEquals("16,500", en.money(16500.4, 0))
        assertEquals("12.50", en.money(12.5, 2))
        assertEquals("12,50", id.money(12.5, 2))
    }

    @Test fun newerRatesAreDueAfterTheEcbPublishesOnWorkingDays() {
        fun at(utc: String) = Money.latestPublished(Instant.parse(utc))
        val thu = LocalDate.of(2026, 11, 19)
        // Thursday 16:00 UTC is 17:00 in Frankfurt: Thursday's are out.
        assertEquals(thu, at("2026-11-19T16:00:00Z"))
        // Friday morning in Jakarta is still before 16:15 in Frankfurt.
        assertEquals(thu, at("2026-11-20T03:00:00Z"))
        assertEquals(thu.plusDays(1), at("2026-11-20T15:20:00Z"))
        // None on the weekend, and Monday's aren't out until the afternoon.
        assertEquals(thu.plusDays(1), at("2026-11-22T20:00:00Z"))
        assertEquals(thu.plusDays(1), at("2026-11-23T08:00:00Z"))
        // Summer time: 16:15 in Frankfurt is 14:15 UTC.
        assertEquals(LocalDate.of(2026, 7, 1), at("2026-07-01T14:30:00Z"))

        val rates = Money.Rates(thu, mapOf("USD" to 1.05))
        assertFalse(Money.isStale(rates, Instant.parse("2026-11-20T03:00:00Z")))
        assertTrue(Money.isStale(rates, Instant.parse("2026-11-20T15:20:00Z")))
    }
}
