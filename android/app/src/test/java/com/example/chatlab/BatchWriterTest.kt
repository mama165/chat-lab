package com.example.chatlab

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class BatchWriterTest {
    @Test
    fun itemsAreWrittenInOrderInFullBatchesThenTheRest() = runBlocking {
        val batches = Collections.synchronizedList(mutableListOf<List<Int>>())
        val writer = BatchWriter(this, intervalMs = 60_000, maxBatch = 3, write = { batches.add(it) })
        (1..7).forEach { writer.submit(it) }
        writer.close()
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), batches.toList())
    }

    @Test
    fun aBatchIsWrittenAfterTheIntervalWithoutWaitingForTheEnd() = runBlocking {
        val written = Collections.synchronizedList(mutableListOf<List<String>>())
        val writer = BatchWriter(this, intervalMs = 50, maxBatch = 100, write = { written.add(it) })
        writer.submit("a")
        withTimeout(3_000) {
            while (written.isEmpty()) delay(10)
        }
        assertEquals(listOf(listOf("a")), written.toList())
        writer.close()
    }

    @Test
    fun aFailedWriteIsReportedAndTheNextBatchesAreStillWritten() = runBlocking {
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val writer = BatchWriter(
            this,
            intervalMs = 60_000,
            maxBatch = 1,
            write = { items ->
                if (items.first() == 1) throw IllegalStateException("disk full")
                written.addAll(items)
            },
            onError = { errors.add(it) },
        )
        writer.submit(1)
        writer.submit(2)
        writer.close()
        assertEquals(listOf(2), written.toList())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is IllegalStateException)
    }

    @Test
    fun closingWithNothingQueuedWritesNothing() = runBlocking {
        var calls = 0
        val writer = BatchWriter<Int>(this, intervalMs = 60_000, maxBatch = 5, write = { calls++ })
        writer.close()
        assertEquals(0, calls)
    }
}
