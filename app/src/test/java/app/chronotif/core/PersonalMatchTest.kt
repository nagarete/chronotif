// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalMatchTest {
    private class C(val name: String, vararg val numbers: String)

    private val people = listOf(
        C("Budi Santoso", "+62 812-3456-7890"),
        C("Ibu", "0811 111"),
        C("Andi", "021 555 0123"),
        C("Ani Budiman"),
    )

    private fun find(q: String) = PersonalMatch.contacts(people, q, { it.name }, { it.numbers.toList() }).map { it.name }

    @Test fun contactsMatchTheStartOfAName() {
        assertEquals(listOf("Budi Santoso", "Ani Budiman"), find("bud"))
        assertEquals(listOf("Andi", "Ani Budiman"), find("an"))
    }

    @Test fun contactsDoNotMatchTheMiddleOfAWord() {
        assertEquals(emptyList<String>(), find("bu").filter { it == "Ibu" })
    }

    @Test fun digitsAlsoFindNumbers() {
        assertEquals(listOf("Andi"), find("5550"))
        assertEquals(listOf("Budi Santoso"), find("3456"))
        assertEquals("Two digits are too few", emptyList<String>(), find("55"))
    }

    @Test fun callsTheDefaultNumberThenAMobile() {
        val home = PersonalMatch.Number("021", isDefault = false, isMobile = false)
        val mobile = PersonalMatch.Number("0812", isDefault = false, isMobile = true)
        val chosen = PersonalMatch.Number("0813", isDefault = true, isMobile = false)
        assertEquals("0813", PersonalMatch.callNumber(listOf(home, mobile, chosen)))
        assertEquals("0812", PersonalMatch.callNumber(listOf(home, mobile)))
        assertEquals("021", PersonalMatch.callNumber(listOf(home)))
        assertEquals(null, PersonalMatch.callNumber(emptyList()))
    }

    private class E(val id: Long, val title: String, val begin: Long, val end: Long = begin + 1)

    private fun events(q: String, all: List<E>, now: Long = 1000) =
        PersonalMatch.events(all, q, now, { it.id }, { it.title }, { it.begin }, { it.end }).map { "${it.title}@${it.begin}" }

    @Test fun eventsAreSoonestFirstThenTheMostRecentPast() {
        val all = listOf(
            E(1, "Dentist", 5000), E(2, "Dentist again", 2000), E(3, "Dentist old", 100), E(4, "Dentist older", 50),
        )
        assertEquals(listOf("Dentist again@2000", "Dentist@5000", "Dentist old@100"), events("dent", all))
    }

    @Test fun anEventStillGoingIsNotPast() {
        assertEquals(listOf("Standup@900"), events("stand", listOf(E(1, "Standup", 900, 1100))))
    }

    @Test fun aRepeatingEventShowsOnceAtItsNextTime() {
        val weekly = listOf(E(7, "Gym", 500), E(7, "Gym", 1500), E(7, "Gym", 2500))
        assertEquals(listOf("Gym@1500"), events("gym", weekly))
    }

    @Test fun eventsMatchWordStarts() {
        assertEquals(listOf("Lunch with Budi@2000"), events("bud", listOf(E(1, "Lunch with Budi", 2000), E(2, "Ibu", 2000))))
    }
}
