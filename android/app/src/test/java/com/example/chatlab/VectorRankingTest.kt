package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorRankingTest {
    @get:Rule val folder = TemporaryFolder()

    private fun vector(seed: Int) = FloatArray(768) { ((seed * 31 + it * 7) % 97) / 97f - 0.5f }

    @Test
    fun theBlockRankingMatchesARankingOfEveryRow() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        val count = 700
        file.writeAll((0 until count).map { it.toLong() to vector(it) })
        val query = vector(12345)
        // Blocks of 256 rows, so the last block is partial.
        val fromBlocks = VectorRanking.topRows(query, count.toLong(), file, k = 15, blockRows = 256)
        val direct = (0 until count)
            .map { it.toLong() to Library.dot(query, vector(it)) }
            .sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
            .take(15)
        assertEquals(direct, fromBlocks)
    }

    @Test
    fun anEmptyStoreGivesNothing() {
        val file = VectorFile(folder.newFile("empty.bin"))
        assertEquals(emptyList<Pair<Long, Float>>(), VectorRanking.topRows(vector(1), 0L, file, k = 5))
    }
}
