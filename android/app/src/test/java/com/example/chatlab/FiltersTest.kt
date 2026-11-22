package com.example.chatlab

import com.example.chatlab.TestData.NOV_2026
import com.example.chatlab.TestData.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    @Test
    fun defaultFiltersAreInactive() {
        val filters = Filters()
        assertTrue(filters.isDefault)
        assertEquals(0, filters.activeCount)
        assertTrue(filters.chips().isEmpty())
        assertEquals(Resemblance.NORMAL, filters.resemblance)
    }

    @Test
    fun activeCountCountsEveryChangedFilter() {
        assertEquals(1, Filters(place = Place.INDOOR).activeCount)
        assertEquals(2, Filters(place = Place.INDOOR, period = Period.THIS_MONTH).activeCount)
        assertEquals(3, Filters(place = Place.INDOOR, period = Period.THIS_MONTH, people = People.ONE).activeCount)
        assertEquals(4, Filters(Place.OUTDOOR, People.MANY, Period.THIS_YEAR, Resemblance.WIDE).activeCount)
    }

    @Test
    fun chipsAreInPageOrderWithLabels() {
        val filters = Filters(Place.INDOOR, People.NONE, Period.THIS_MONTH, Resemblance.CLOSE)
        assertEquals(
            listOf("Intérieur", "Sans personne", "Ce mois", "Ressemblance proche"),
            filters.chips().map { it.label },
        )
    }

    @Test
    fun removingAChipOnlyResetsThatFilter() {
        val filters = Filters(place = Place.INDOOR, period = Period.THIS_MONTH)
        val withoutPlace = filters.chips().first { it.label == "Intérieur" }.without
        assertEquals(Filters(period = Period.THIS_MONTH), withoutPlace)
        assertEquals(1, withoutPlace.activeCount)
    }

    @Test
    fun peopleChipLabels() {
        assertEquals("1 personne", Filters(people = People.ONE).chips().single().label)
        assertEquals("2 personnes et plus", Filters(people = People.MANY).chips().single().label)
    }

    @Test
    fun placeLabels() {
        assertEquals("Partout", Filters.placeLabel(Place.ALL))
        assertEquals("Extérieur", Filters.placeLabel(Place.OUTDOOR))
    }

    @Test
    fun matchesPlace() {
        val inside = entry("a", indoor = true)
        assertTrue(Filters(place = Place.INDOOR).matches(inside, NOV_2026))
        assertFalse(Filters(place = Place.OUTDOOR).matches(inside, NOV_2026))
        assertTrue(Filters().matches(inside, NOV_2026))
    }

    @Test
    fun matchesPeople() {
        val none = entry("a", faces = 0)
        val one = entry("b", faces = 1)
        val three = entry("c", faces = 3)
        assertTrue(Filters(people = People.NONE).matches(none, NOV_2026))
        assertFalse(Filters(people = People.NONE).matches(one, NOV_2026))
        assertTrue(Filters(people = People.ONE).matches(one, NOV_2026))
        assertFalse(Filters(people = People.ONE).matches(three, NOV_2026))
        assertTrue(Filters(people = People.MANY).matches(three, NOV_2026))
        assertFalse(Filters(people = People.MANY).matches(one, NOV_2026))
        assertTrue(Filters(people = People.ANY).matches(three, NOV_2026))
    }

    @Test
    fun matchesPeriod() {
        val thisMonth = entry("a", taken = "05/11/2026 09:00")
        val thisYear = entry("b", taken = "14/03/2026")
        val lastYear = entry("c", taken = "05/11/2025")
        val month = Filters(period = Period.THIS_MONTH)
        val year = Filters(period = Period.THIS_YEAR)
        assertTrue(month.matches(thisMonth, NOV_2026))
        assertFalse(month.matches(thisYear, NOV_2026))
        assertFalse(month.matches(lastYear, NOV_2026))
        assertTrue(year.matches(thisMonth, NOV_2026))
        assertTrue(year.matches(thisYear, NOV_2026))
        assertFalse(year.matches(lastYear, NOV_2026))
    }

    @Test
    fun undatedPhotosOnlyPassTheAllPeriod() {
        val undated = entry("a", taken = "")
        assertTrue(Filters().matches(undated, NOV_2026))
        assertFalse(Filters(period = Period.THIS_YEAR).matches(undated, NOV_2026))
    }

    @Test
    fun resemblanceDoesNotFilterPhotosItself() {
        assertTrue(Filters(resemblance = Resemblance.CLOSE).matches(entry("a"), NOV_2026))
    }

    @Test
    fun resemblanceFloorsAreOrdered() {
        assertTrue(Resemblance.CLOSE.floor > Resemblance.NORMAL.floor)
        assertTrue(Resemblance.NORMAL.floor > Resemblance.WIDE.floor)
        assertTrue(Resemblance.WIDE.floor > MoreResults.FLOOR)
    }
}
