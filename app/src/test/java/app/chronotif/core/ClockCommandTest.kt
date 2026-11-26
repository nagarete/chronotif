// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import app.chronotif.core.ClockCommand.Alarm
import app.chronotif.core.ClockCommand.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ClockCommandTest {
    private fun parse(q: String) = ClockCommand.parse(q)

    @Test fun timersInEnglish() {
        assertEquals(Timer(600), parse("timer 10 min"))
        assertEquals(Timer(600), parse("10 min timer"))
        assertEquals(Timer(600), parse("set a timer for 10 minutes"))
        assertEquals(Timer(600), parse("timer 10"))
        assertEquals(Timer(90), parse("timer 90s"))
        assertEquals(Timer(5400), parse("timer 1h30"))
        assertEquals(Timer(5400), parse("timer 1h30m"))
        assertEquals(Timer(5400), parse("timer 1 hour 30 minutes"))
        assertEquals(Timer(150), parse("Timer 2m30"))
        assertEquals(Timer(5400), parse("timer 1.5 hours"))
    }

    @Test fun timersInIndonesian() {
        assertEquals(Timer(600), parse("timer 10 menit"))
        assertEquals(Timer(5400), parse("timer 1 jam 30 menit"))
        assertEquals(Timer(5400), parse("pasang timer 1,5 jam"))
        assertEquals(Timer(45), parse("timer 45 detik"))
    }

    @Test fun aTimerNeedsALengthAndAtMostADay() {
        assertNull(parse("timer"))
        assertNull(parse("timer pasta"))
        assertNull(parse("timer 0 min"))
        assertNull(parse("timer 25 hours"))
        assertEquals(Timer(ClockCommand.MAX_TIMER_SECONDS), parse("timer 24 h"))
    }

    @Test fun aBareTimeIsTheMorning() {
        assertEquals(Alarm(6, 30), parse("alarm 6:30"))
        assertEquals(Alarm(6, 30), parse("alarm 6.30"))
        assertEquals(Alarm(6, 0), parse("alarm 6"))
        assertEquals(Alarm(18, 30), parse("alarm 18:30"))
        assertEquals(Alarm(6, 30), parse("6:30 alarm"))
        assertEquals(Alarm(7, 0), parse("set an alarm for 7"))
        assertEquals(Alarm(7, 15), parse("wake me up at 7:15"))
    }

    @Test fun amAndPm() {
        assertEquals(Alarm(18, 30), parse("alarm 6:30 pm"))
        assertEquals(Alarm(18, 30), parse("alarm 6:30pm"))
        assertEquals(Alarm(6, 30), parse("alarm 6:30 am"))
        assertEquals(Alarm(0, 0), parse("alarm 12 am"))
        assertEquals(Alarm(12, 0), parse("alarm 12 pm"))
        assertNull(parse("alarm 18:30 pm"))
    }

    @Test fun alarmsInIndonesian() {
        assertEquals(Alarm(6, 0), parse("alarm jam 6"))
        assertEquals(Alarm(5, 30), parse("bangunkan saya jam 5.30"))
        assertEquals(Alarm(19, 0), parse("alarm jam 7 malam"))
        assertEquals(Alarm(13, 0), parse("alarm jam 1 siang"))
        assertEquals(Alarm(16, 0), parse("alarm pukul 4 sore"))
        assertEquals(Alarm(6, 0), parse("alarm jam 6 pagi"))
    }

    @Test fun notAnAlarm() {
        assertNull(parse("alarm"))
        assertNull(parse("alarm clock"))
        assertNull(parse("alarm 25:00"))
        assertNull(parse("alarm 6:75"))
        assertNull(parse("6:30"))
    }

    @Test fun answerPutsTheClockFirst() {
        val en = Numbers(Locale.US)
        assertEquals(Answer.Clock(Timer(600)), Answer.of("timer 10 min", en))
        assertEquals(Answer.Clock(Alarm(6, 30)), Answer.of("alarm 6:30", en))
    }

    @Test fun typedPhoneNumbers() {
        assertEquals("+6281234567890", TypedNumber.of("+62 812-3456-7890"))
        assertEquals("081234567890", TypedNumber.of("0812 3456 7890"))
        assertEquals("081234567890", TypedNumber.of("081234567890"))
        assertEquals("1234567", TypedNumber.of("1234567"))
        assertNull("Too short", TypedNumber.of("123456"))
        assertNull("A sum", TypedNumber.of("12-3456789"))
        assertNull(TypedNumber.of("0812 call"))
        assertNull(TypedNumber.of("1234567890123456"))
    }

    @Test fun aPhoneNumberIsNotMath() {
        val en = Numbers(Locale.US)
        assertNull(Answer.of("0812-3456-7890", en))
        assertTrue(Answer.of("12-3", en) is Answer.Math)
    }
}
