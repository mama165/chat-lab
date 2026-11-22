package com.example.chatlab

import com.example.chatlab.TestData.entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun parse_readsEveryField() {
        val text = """[{"id":"a","name":"a.jpg","path":"/p/a.jpg","indoor":true,"faces":2,"text":"Facture",
            "taken":"18/11/2026 10:00","source":"content://a","vectors":[[0.5,0.25]]}]"""
        val entries = Library.parse(text)
        assertEquals(1, entries.size)
        val a = entries[0]
        assertEquals("a", a.id)
        assertEquals("a.jpg", a.name)
        assertEquals("/p/a.jpg", a.path)
        assertEquals(true, a.indoor)
        assertEquals(2, a.faces)
        assertEquals("Facture", a.text)
        assertEquals("18/11/2026 10:00", a.taken)
        assertEquals("content://a", a.source)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), a.vector, 0f)
    }

    @Test
    fun parse_missingFieldsGetDefaults() {
        val a = Library.parse("""[{"id":"a","name":"a.jpg","path":"/a"}]""").single()
        assertNull(a.indoor)
        assertEquals(0, a.faces)
        assertEquals("", a.text)
        assertEquals("", a.source)
        assertNull(a.vector)
    }

    @Test
    fun parse_outdoorIsFalse() {
        assertEquals(false, Library.parse("""[{"id":"a","indoor":false}]""").single().indoor)
    }

    @Test
    fun parse_nullBlankOrBrokenTextGivesEmptyList() {
        assertTrue(Library.parse(null).isEmpty())
        assertTrue(Library.parse("").isEmpty())
        assertTrue(Library.parse("pas du json").isEmpty())
        assertTrue(Library.parse("{}").isEmpty())
    }

    @Test
    fun parse_skipsItemsThatAreNotObjects() {
        assertEquals(1, Library.parse("""[1, "x", {"id":"a"}]""").size)
    }

    @Test
    fun fold_lowercasesAndRemovesAccentsAndPunctuation() {
        assertEquals("ete a l ecole", Library.fold("Été, à l'École !"))
        assertEquals("wi fi bar", Library.fold("Wi-Fi  bar"))
        assertEquals("", Library.fold("  ...  "))
    }

    @Test
    fun searchWords_needsEveryWord() {
        val entries = listOf(entry("a", "facture du gaz"), entry("b", "facture"), entry("c", "gaz"))
        val found = Library.searchWords(entries, "facture gaz", Place.ALL).map { it.entry.id }
        assertEquals(listOf("a"), found)
    }

    @Test
    fun searchWords_ignoresCaseAccentsAndMatchesPartialWords() {
        val entries = listOf(entry("a", "Crème brûlée"), entry("b", "Facture"))
        assertEquals(listOf("a"), Library.searchWords(entries, "creme BRULEE", Place.ALL).map { it.entry.id })
        assertEquals(listOf("b"), Library.searchWords(entries, "fact", Place.ALL).map { it.entry.id })
    }

    @Test
    fun searchWords_alsoLooksInTheFileName() {
        val entries = listOf(entry("a", "", name = "ticket_wifi.jpg"))
        assertEquals(listOf("a"), Library.searchWords(entries, "wifi", Place.ALL).map { it.entry.id })
    }

    @Test
    fun searchWords_putsPhotosWithTheWordInTheTextFirst() {
        val entries = listOf(entry("byName", "", name = "gaufre.jpg"), entry("byText", "gaufre"))
        assertEquals(listOf("byText", "byName"), Library.searchWords(entries, "gaufre", Place.ALL).map { it.entry.id })
    }

    @Test
    fun searchWords_hasNoScore() {
        assertNull(Library.searchWords(listOf(entry("a", "gaufre")), "gaufre", Place.ALL).single().score)
    }

    @Test
    fun searchWords_filtersByPlace() {
        val entries = listOf(entry("in", "gaufre", indoor = true), entry("out", "gaufre", indoor = false), entry("unknown", "gaufre"))
        assertEquals(listOf("in"), Library.searchWords(entries, "gaufre", Place.INDOOR).map { it.entry.id })
        assertEquals(listOf("out"), Library.searchWords(entries, "gaufre", Place.OUTDOOR).map { it.entry.id })
        assertEquals(3, Library.searchWords(entries, "gaufre", Place.ALL).size)
    }

    @Test
    fun rankDescription_sortsByScoreAndSkipsPhotosWithoutVector() {
        val q = floatArrayOf(1f, 0f)
        val entries = listOf(
            entry("low", vector = floatArrayOf(0.1f, 0.9f)),
            entry("none"),
            entry("high", vector = floatArrayOf(0.9f, 0.1f)),
        )
        val ranked = Library.rankDescription(entries, q, Place.ALL, null)
        assertEquals(listOf("high", "low"), ranked.map { it.entry.id })
        assertEquals(0.9f, ranked[0].score!!, 1e-6f)
    }

    @Test
    fun rankDescription_appliesPlaceAndPeople() {
        val q = floatArrayOf(1f)
        val entries = listOf(
            entry("in2", indoor = true, faces = 2, vector = floatArrayOf(1f)),
            entry("in1", indoor = true, faces = 1, vector = floatArrayOf(1f)),
            entry("out2", indoor = false, faces = 2, vector = floatArrayOf(1f)),
        )
        assertEquals(listOf("in2"), Library.rankDescription(entries, q, Place.INDOOR, 2).map { it.entry.id })
    }

    @Test
    fun aboveThreshold_keepsScoresAboveFloorAndNearTheBest() {
        val ranked = listOf(TestData.match("a", 0.20f), TestData.match("b", 0.17f), TestData.match("c", 0.15f))
        assertEquals(listOf("a", "b"), Library.aboveThreshold(ranked, 0.08f).map { it.entry.id })
        assertEquals(listOf("a"), Library.aboveThreshold(ranked, 0.18f).map { it.entry.id })
        assertEquals(3, Library.aboveThreshold(ranked, 0.08f, margin = 1f).size)
    }

    @Test
    fun aboveThreshold_emptyStaysEmpty() {
        assertTrue(Library.aboveThreshold(emptyList(), 0.08f).isEmpty())
    }

    @Test
    fun criteria_readsPeopleAndPlace() {
        assertEquals(Criteria(2, null), Library.criteria("deux personnes à table"))
        assertEquals(Criteria(3, null), Library.criteria("3 visages"))
        assertEquals(Criteria(1, Place.INDOOR), Library.criteria("une personne en intérieur"))
        assertEquals(Criteria(null, Place.OUTDOOR), Library.criteria("pique-nique dehors"))
        assertEquals(Criteria(null, Place.OUTDOOR), Library.criteria("concert en plein air"))
        assertEquals(Criteria(null, null), Library.criteria("une plage"))
    }

    @Test
    fun dot_multipliesComponentWise() {
        assertEquals(11f, Library.dot(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)), 0f)
    }

    @Test
    fun inPlace_unknownPlaceOnlyMatchesAll() {
        val unknown = entry("u")
        assertTrue(Library.inPlace(unknown, Place.ALL))
        assertTrue(!Library.inPlace(unknown, Place.INDOOR))
        assertTrue(!Library.inPlace(unknown, Place.OUTDOOR))
    }

    @Test
    fun parseReadsFolderTypeAndExactTime() {
        val text = """[{"id":"a","name":"a.jpg","path":"/p","folder":"Camera","mime":"image/jpeg","taken_ms":1700000000000,"portrait":true,"taken":"14/11/2023 22:13"}]"""
        val a = Library.parse(text).single()
        assertEquals("Camera", a.folder)
        assertEquals("image/jpeg", a.mime)
        assertEquals(1700000000000L, a.takenMs)
        assertTrue(a.closeUp)
    }

    @Test
    fun parseDerivesTheTimeFromAnOldCarnet() {
        val text = """[{"id":"a","name":"a.jpg","path":"/p","taken":"18/11/2026 13:42"}]"""
        val a = Library.parse(text, TestData.UTC).single()
        assertEquals(TestData.utc(2026, 11, 18, 13, 42), a.takenMs)
        assertEquals("", a.folder)
        assertEquals("", a.mime)
        assertFalse(a.closeUp)
    }

    @Test
    fun parseGivesZeroWhenThereIsNoDate() {
        assertEquals(0L, Library.parse("""[{"id":"a","name":"a","path":"/p"}]""").single().takenMs)
        assertEquals(0L, Library.parse("""[{"id":"a","name":"a","path":"/p","taken":"nimporte quoi"}]""").single().takenMs)
    }
}
