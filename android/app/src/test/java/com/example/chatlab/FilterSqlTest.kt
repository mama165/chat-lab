package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSqlTest {
    private val time = When(now = 1_000_000_000_000L)

    @Test
    fun noFilterMeansEverything() {
        val q = FilterSql.query(Filters(), time)
        assertEquals("SELECT address FROM photo WHERE 1 = 1 ORDER BY vector_row ASC", q.sql)
        assertTrue(q.args.isEmpty())
    }

    @Test
    fun thePlaceAndThePeopleBecomeConditions() {
        val indoor = FilterSql.query(Filters(place = Place.INDOOR), time).sql
        assertTrue(indoor.contains("indoor = 1"))
        assertTrue(FilterSql.query(Filters(place = Place.OUTDOOR), time).sql.contains("indoor = 0"))
        assertTrue(FilterSql.query(Filters(people = People.NONE), time).sql.contains("faces = 0"))
        assertTrue(FilterSql.query(Filters(people = People.ONE), time).sql.contains("faces = 1"))
        assertTrue(FilterSql.query(Filters(people = People.MANY), time).sql.contains("faces >= 2"))
    }


    @Test
    fun foldersAndTypesUseOnePlaceholderEach() {
        val q = FilterSql.query(
            Filters(folders = setOf("Camera", "Movies"), types = setOf("image/jpeg")),
            time,
        )
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        assertTrue(q.args.containsAll(listOf("Camera", "Movies", "image/jpeg")))
    }

    @Test
    fun aPresetIsABetweenOnTheDate() {
        val q = FilterSql.query(Filters(date = DateFilter(preset = DatePreset.LAST_24H)), time)
        assertTrue(q.sql.contains("taken_ms BETWEEN "))
    }

    @Test
    fun theKindUsesThePictureTypes() {
        val image = FilterSql.query(Filters(kind = FileKind.IMAGE), time)
        assertEquals(FileSniffer.PICTURES.size, image.args.size)
        val pdf = FilterSql.query(Filters(kind = FileKind.PDF), time)
        assertEquals(listOf(FileSniffer.PDF), pdf.args)
    }
}
