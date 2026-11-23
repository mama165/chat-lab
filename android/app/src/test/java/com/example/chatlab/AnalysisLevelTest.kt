package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisLevelTest {
    @Test
    fun each_level_has_its_number_of_workers() {
        assertEquals(1, AnalysisLevel.ECONOMIQUE.workers)
        assertEquals(2, AnalysisLevel.EQUILIBRE.workers)
        assertEquals(3, AnalysisLevel.PUISSANT.workers)
        assertEquals(4, AnalysisLevel.MAXIMAL.workers)
    }

    @Test
    fun saved_key_is_read_back_and_unknown_keys_fall_back_to_the_default() {
        AnalysisLevel.entries.forEach { assertEquals(it, AnalysisLevel.fromKey(it.key)) }
        assertEquals(AnalysisLevel.EQUILIBRE, AnalysisLevel.fromKey(null))
        assertEquals(AnalysisLevel.EQUILIBRE, AnalysisLevel.fromKey("inconnu"))
    }

    @Test
    fun labels_are_the_french_names() {
        assertEquals(listOf("Économique", "Équilibré", "Puissant", "Maximal"), AnalysisLevel.entries.map { it.label })
    }
}
