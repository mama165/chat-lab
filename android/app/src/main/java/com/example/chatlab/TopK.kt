package com.example.chatlab

import java.util.PriorityQueue

/**
 * The [k] best keys by score, from a stream: only k entries are held at a time. A higher score is better;
 * on equal scores the smaller key is better, so the result is always the same for the same scores.
 */
class TopK<T : Comparable<T>>(private val k: Int) {
    private class Entry<T>(val key: T, val score: Float)

    // The head of the queue is the worst entry held, the one to replace first.
    private val heap = PriorityQueue<Entry<T>>(compareBy<Entry<T>> { it.score }.thenByDescending { it.key })

    fun offer(key: T, score: Float) {
        if (k <= 0) return
        if (heap.size < k) {
            heap.add(Entry(key, score))
        } else if (better(key, score, heap.peek())) {
            heap.poll()
            heap.add(Entry(key, score))
        }
    }

    /** The kept keys, best first. */
    fun sorted(): List<Pair<T, Float>> =
        heap.sortedWith(compareByDescending<Entry<T>> { it.score }.thenBy { it.key }).map { it.key to it.score }

    private fun better(key: T, score: Float, worst: Entry<T>): Boolean =
        score > worst.score || (score == worst.score && key < worst.key)
}
