package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSearchTest {
    @Test
    fun wordsAreNormalisedLikeTheStoredText() {
        assertEquals(listOf("electricite", "facture"), WordSearch.words("Électricité  FACTURE"))
    }

    @Test
    fun eachWordNeedsAMatchInTheTextOrTheName() {
        val query = WordSearch.query(listOf("facture", "edf"), Place.ALL)
        // Two words: two matches on the text or the name, plus the score, which uses the text only.
        assertEquals(listOf("%facture%", "%facture%", "%edf%", "%edf%", "%facture%", "%edf%"), query.args)
        assertTrue(query.sql.contains("text_fold LIKE ? ESCAPE '\\' OR name_fold LIKE ? ESCAPE '\\'"))
    }

    @Test
    fun thePlaceIsAFilterOnIndoor() {
        assertTrue(WordSearch.query(emptyList(), Place.INDOOR).sql.contains("indoor = 1"))
        assertTrue(WordSearch.query(emptyList(), Place.OUTDOOR).sql.contains("indoor = 0"))
        assertTrue(!WordSearch.query(emptyList(), Place.ALL).sql.contains("indoor"))
    }

    @Test
    fun theOrderCountsTheWordsInTheText() {
        val query = WordSearch.query(listOf("a", "b"), Place.ALL)
        assertTrue(query.sql.contains("ORDER BY ((CASE WHEN text_fold LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END) + (CASE"))
        assertTrue(query.sql.endsWith("DESC, vector_row ASC"))
    }

    @Test
    fun theWildcardsInAWordAreTakenLiterally() {
        assertEquals("%100\\%%", WordSearch.pattern("100%"))
        assertEquals("%a\\_b%", WordSearch.pattern("a_b"))
        assertEquals("%a\\\\b%", WordSearch.pattern("a\\b"))
    }

    @Test
    fun thePlaceholdersMatchTheArguments() {
        val query = WordSearch.query(listOf("a", "b", "c"), Place.INDOOR)
        assertEquals(query.sql.count { it == '?' }, query.args.size)
    }
}
