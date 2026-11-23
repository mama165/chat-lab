package com.example.chatlab

import kotlin.math.max
import kotlin.math.roundToInt

/** Text reading computations that depend neither on OpenCV nor on the model. */
object OcrMath {
    /**
     * CTC decoding: at each step, the most probable symbol; repetitions and the blank
     * (index 0) are ignored. Returns the text and the average confidence of the kept characters.
     */
    fun decode(steps: Array<FloatArray>, vocabulary: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var previous = -1
        var confidence = 0.0
        var kept = 0
        for (step in steps) {
            if (step.isEmpty()) continue
            var best = 0
            for (i in step.indices) if (step[i] > step[best]) best = i
            if (best != 0 && best != previous && best < vocabulary.size) {
                text.append(vocabulary[best])
                confidence += step[best]
                kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else (confidence / kept).toFloat()
    }

    /** Rounded to the nearest multiple of 32, at least 32, as the detector expects. */
    fun roundTo32(value: Double): Int = max(32, (value / 32).roundToInt() * 32)
}
