package com.example.chatlab

import java.util.concurrent.ConcurrentHashMap

/**
 * The time each stage of the analysis of the photos took, in milliseconds, added up over the
 * whole run. The workers add to it at the same time, so it is thread-safe.
 */
class StageTimes {
    private val totals = ConcurrentHashMap<String, Long>()

    fun add(stage: String, millis: Long) {
        totals.merge(stage, millis, Long::plus)
    }

    fun totals(): Map<String, Long> = totals.toMap()

    companion object {
        const val DECODE = "decodage"
        const val IMAGE = "image"
        const val FACES = "visages"
        const val TEXT = "texte"
    }
}
