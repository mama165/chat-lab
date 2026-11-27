package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun newestFirst() {
        val list = RecentSearches.add(RecentSearches.add(emptyList(), "wifi"), "gaufre")
        assertEquals(listOf("gaufre", "wifi"), list)
    }

    @Test
    fun noDuplicatesIgnoringCaseAndAccents() {
        val list = RecentSearches.add(listOf("crème", "wifi"), "CREME")
        assertEquals(listOf("CREME", "wifi"), list)
    }

    @Test
    fun trimsAndCollapsesSpaces() {
        assertEquals(listOf("une plage"), RecentSearches.add(emptyList(), "  une   plage "))
    }

    @Test
    fun ignoresTooShortSearches() {
        assertEquals(listOf("wifi"), RecentSearches.add(listOf("wifi"), "a"))
        assertEquals(listOf("wifi"), RecentSearches.add(listOf("wifi"), "   "))
    }

    @Test
    fun keepsAtMostMax() {
        var list = emptyList<String>()
        for (i in 1..12) list = RecentSearches.add(list, "mot$i")
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("mot12", list.first())
        assertEquals(3, RecentSearches.add(listOf("a1", "b1", "c1"), "d1", max = 3).size)
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val list = listOf("gaufre", "une plage")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        assertEquals(emptyList<String>(), RecentSearches.decode("\n \n"))
    }

    @Test
    fun removeTakesTheWordOutOfTheList() {
        assertEquals(listOf("wifi", "gaufre"), RecentSearches.remove(listOf("facture", "wifi", "gaufre"), "facture"))
    }
}
