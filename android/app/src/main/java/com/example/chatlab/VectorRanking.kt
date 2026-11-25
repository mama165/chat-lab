package com.example.chatlab

/** The best rows for a query, read from vectors.bin in blocks: the whole file is never held in memory. */
object VectorRanking {
    fun topRows(query: FloatArray, rowCount: Long, file: VectorFile, k: Int, blockRows: Int = 256): List<Pair<Long, Float>> {
        val top = TopK<Long>(k)
        var first = 0L
        while (first < rowCount) {
            val count = minOf(blockRows.toLong(), rowCount - first).toInt()
            val block = file.readBlock(first, count)
            for (i in 0 until count) {
                top.offer(first + i, dot(query, block, i * query.size))
            }
            first += count
        }
        return top.sorted()
    }

    /** The same sum as the ranking in Library.dot, in the same order: the scores are comparable. */
    fun dot(query: FloatArray, block: FloatArray, offset: Int): Float {
        var sum = 0f
        for (i in query.indices) sum += query[i] * block[offset + i]
        return sum
    }
}
