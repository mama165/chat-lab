package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSheetTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val time = When(0L, utc)

    @Test
    fun sheetValueIsEmptyUntilTheCategoryIsSet() {
        assertEquals("", sheetValue(SheetCategory.DATE, Filters()))
        assertEquals("", sheetValue(SheetCategory.TYPE, Filters()))
    }

    @Test
    fun sheetValueShowsTheChosenValuePerCategory() {
        val filters = Filters(
            date = DateFilter(DatePreset.LAST_7D),
            place = Place.INDOOR,
            people = People.MANY,
            kind = FileKind.PDF,
        )
        assertEquals("7 derniers jours", sheetValue(SheetCategory.DATE, filters))
        assertEquals("Intérieur", sheetValue(SheetCategory.PLACE, filters))
        assertEquals("2 et plus", sheetValue(SheetCategory.PEOPLE, filters))
        assertEquals("PDF", sheetValue(SheetCategory.TYPE, filters))
    }

    @Test
    fun sheetValueOfACustomRangeIsItsLabel() {
        val range = DateRange(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 17))
        val label = sheetValue(SheetCategory.DATE, Filters(date = DateFilter(range = range)))
        assertTrue(label.isNotEmpty())
        assertFalse(label.startsWith("Date"))
    }

    @Test
    fun datePresetsAreInTheSheetOrder() {
        assertEquals(
            listOf(
                DatePreset.ALL, DatePreset.TODAY, DatePreset.LAST_7D, DatePreset.LAST_30D, DatePreset.LAST_12M,
            ),
            SheetDatePresets,
        )
    }

    @Test
    fun choosingTheSamePlaceAgainRemovesIt() {
        assertEquals(Place.INDOOR, toggledPlace(Place.ALL, Place.INDOOR))
        assertEquals(Place.ALL, toggledPlace(Place.INDOOR, Place.INDOOR))
        assertEquals(Place.OUTDOOR, toggledPlace(Place.INDOOR, Place.OUTDOOR))
    }

    @Test
    fun peopleSheetOffersTheFourChoices() {
        assertEquals(listOf("Peu importe", "Aucune", "1", "2 et plus"), People.entries.map { it.label })
    }

    @Test
    fun fileKindSheetOffersAllImagePdf() {
        assertEquals(listOf("Tous les types", "Image", "PDF"), FileKind.entries.map { it.label })
    }
}
