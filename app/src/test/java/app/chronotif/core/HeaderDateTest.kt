// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderDateTest {
    @Test
    fun theDefaultShowsTheFullWeekdayDayAndMonth() =
        assertEquals("EEEEdMMMM", HeaderDate.skeleton(weekday = true, year = false, shortNames = false))

    @Test
    fun shortNamesShortenBothWeekdayAndMonth() =
        assertEquals("EEEdMMM", HeaderDate.skeleton(weekday = true, year = false, shortNames = true))

    @Test
    fun theYearIsAddedOnlyWhenChosen() {
        assertEquals("EEEEdMMMMy", HeaderDate.skeleton(weekday = true, year = true, shortNames = false))
        assertEquals("dMMMy", HeaderDate.skeleton(weekday = false, year = true, shortNames = true))
    }

    @Test
    fun theDayAndMonthAlwaysShow() =
        assertEquals("dMMMM", HeaderDate.skeleton(weekday = false, year = false, shortNames = false))
}
