package com.example.chatlab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One writer for many producers. The items are queued, and a single task writes them in batches: a batch
 * is written when [maxBatch] items are waiting, or [intervalMs] after its first item, whichever comes first.
 * [submit] waits only when the queue is full, so the producers never wait for the disk otherwise.
 * [close] writes what is left, then returns. A failed write is reported, and the next batches are still written.
 */
class BatchWriter<T>(
    scope: CoroutineScope,
    private val intervalMs: Long,
    private val maxBatch: Int,
    private val write: (List<T>) -> Unit,
    private val onError: (Exception) -> Unit = {},
) {
    private val queue = Channel<T>(capacity = maxBatch * 4)
    private val task: Job = scope.launch { run() }

    suspend fun submit(item: T) {
        queue.send(item)
    }

    suspend fun close() {
        queue.close()
        task.join()
    }

    private suspend fun run() {
        val batch = ArrayList<T>(maxBatch)
        var open = true
        while (open) {
            val first = queue.receiveCatching().getOrNull() ?: break
            batch += first
            val deadline = System.currentTimeMillis() + intervalMs
            while (batch.size < maxBatch) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                // A timeout cancels only the wait: no item is lost, the next one stays in the queue.
                val received = withTimeoutOrNull(left) { queue.receiveCatching() } ?: break
                val item = received.getOrNull()
                if (item == null) {
                    open = false
                    break
                }
                batch += item
            }
            flush(batch)
        }
    }

    private fun flush(batch: MutableList<T>) {
        if (batch.isEmpty()) return
        val items = batch.toList()
        batch.clear()
        try {
            write(items)
        } catch (e: Exception) {
            onError(e)
        }
    }
}
