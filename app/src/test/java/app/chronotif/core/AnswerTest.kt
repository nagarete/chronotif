// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AnswerTest {
    private val en = Numbers(Locale.US)
    private val id = Numbers(Locale.forLanguageTag("id-ID"))

    private fun math(q: String, n: Numbers = en) = (Answer.of(q, n) as? Answer.Math)?.value
    private fun converted(q: String) = (Answer.of(q, en) as? Answer.Converted)?.conversion

    // --- Numbers

    @Test fun numbersFollowThePhonesLanguage() {
        assertEquals(50000.0, en.parse("50,000"))
        assertEquals(1.5, en.parse("1.5"))
        assertEquals(50000.0, id.parse("50.000"))
        assertEquals(1.5, id.parse("1,5"))
        assertEquals("812,345", en.format(812345.0, 10))
        assertEquals("812.345", id.format(812345.0, 10))
        assertEquals("812345", en.plain(812345.0, 10))
    }

    @Test fun aSeparatorThatCantBeGroupingIsTheDecimalPoint() {
        assertEquals(1.5, en.parse("1,5"))
        assertEquals(1.5, id.parse("1.5"))
        assertNull("Two decimal points", en.parse("1.2.3"))
    }

    @Test fun tenSignificantDigitsWithTrailingZerosDropped() {
        assertEquals("0.3333333333", en.format(1.0 / 3, 10))
        assertEquals("2.5", en.format(10.0 / 4, 10))
        assertEquals("0.3", en.format(0.1 + 0.2, 10))
    }

    // --- Math

    @Test fun mathFollowsPrecedenceAndBrackets() {
        assertEquals(93.0, math("12*7.5+3"))
        assertEquals(93.0, math("12 × 7.5 + 3"))
        assertEquals(36.0, math("12x3"))
        assertEquals(20.0, math("(2+3)*4"))
        assertEquals(512.0, math("2^3^2"))
        assertEquals(2.5, math("10 ÷ 4"))
        assertEquals(-1.0, math("2-3"))
    }

    @Test fun percentIsOfTheLeftSideWhenAddedOrTaken() {
        assertEquals(230.0, math("200 + 15%")!!, 1e-9)
        assertEquals(170.0, math("200 - 15%")!!, 1e-9)
        assertEquals(30.0, math("200 * 15%")!!, 1e-9)
    }

    @Test fun groupedNumbersInMath() {
        assertEquals(100000.0, math("50,000*2"))
        assertEquals(100000.0, math("50.000*2", id))
        assertEquals(3.0, math("1,5*2", id))
    }

    @Test fun aLoneNumberOrHalfAnExpressionIsNotMath() {
        assertNull(math("50,000"))
        assertNull(math("-5"))
        assertNull(math("12*"))
        assertNull(math("(2+3"))
        assertNull(math("1/0"))
        assertNull(math("spotify"))
    }

    // --- Units

    @Test fun unitsConvertWithATarget() {
        val c = converted("5 km in mi")!!
        assertEquals("mi", c.to.symbol)
        assertEquals(3.10686, c.result, 1e-5)
        assertEquals(86.0, converted("30 c to f")!!.result, 1e-9)
        assertEquals(12.7, converted("5 in in cm")!!.result, 1e-9)
        assertEquals(2.204622, converted("1kg to pounds")!!.result, 1e-6)
    }

    @Test fun withoutATargetAUnitGoesToItsUsualCounterpart() {
        assertEquals("°F", converted("30 c")!!.to.symbol)
        assertEquals("lb", converted("2kg")!!.to.symbol)
        assertEquals("mph", converted("100 km/h")!!.to.symbol)
        assertEquals(-40.0, converted("-40 f")!!.result, 1e-9)
    }

    @Test fun unitsOfDifferentKindsDontConvert() {
        assertNull(converted("5 km to kg"))
        assertNull(converted("5 apples"))
    }

    // --- Time

    @Test fun timeInAPlace() {
        val tokyo = (Answer.of("time in tokyo", en) as Answer.Time).place
        assertEquals("Asia/Tokyo", tokyo.zone.id)
        assertEquals("Tokyo", tokyo.name)
        assertEquals("Asia/Makassar", (Answer.of("bali time", en) as Answer.Time).place.zone.id)
        assertEquals("America/New_York", (Answer.of("Time in New York?", en) as Answer.Time).place.zone.id)
        assertEquals("Asia/Tokyo", (Answer.of("time in japan", en) as Answer.Time).place.zone.id)
        assertNull("A place alone isn't a time question", Answer.of("tokyo", en))
        assertNull(Answer.of("time in narnia", en))
    }

    @Test fun minutesAheadCountsHalfHours() {
        assertEquals(120, Answer.minutesAhead(7 * 3600, 9 * 3600))
        assertEquals(-90, Answer.minutesAhead(7 * 3600, (5.5 * 3600).toInt()))
    }

    // --- Web

    @Test fun addressesOpenDirectlyAndTheRestIsSearched() {
        assertTrue(WebQuery.isAddress("klikbca.com"))
        assertTrue(WebQuery.isAddress("github.com/nagarete/chronotif"))
        assertTrue(WebQuery.isAddress("https://example.org"))
        assertFalse(WebQuery.isAddress("1.5"))
        assertFalse(WebQuery.isAddress("best ramen jakarta"))
        assertFalse(WebQuery.isAddress("hello"))
        assertEquals("https://klikbca.com", WebQuery.address("klikbca.com"))
        assertEquals("https://duckduckgo.com/?q=nasi+goreng+%26+teh", WebQuery.search(WebQuery.Engine.DUCKDUCKGO, "nasi goreng & teh"))
    }
}
