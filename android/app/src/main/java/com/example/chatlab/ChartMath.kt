package com.example.chatlab

/** Geometry of the curves of an analysis: pure, so that it can be tested without drawing. */
object ChartMath {
    /** X position in [0, 1] of a time, for a run lasting [durationSec]. */
    fun xFraction(tSec: Int, durationSec: Int): Float =
        if (durationSec <= 0) 0f else (tSec.toFloat() / durationSec).coerceIn(0f, 1f)

    /** Y position in [0, 1] (0 = bottom) of a value on a scale from 0 to [max]. */
    fun yFraction(value: Float, max: Float): Float =
        if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)

    /** Times, in seconds, where a vertical graduation is written: every half hour, from 0. */
    fun halfHourMarks(durationSec: Int): List<Int> =
        if (durationSec <= 0) emptyList() else (0..durationSec step 1800).toList()

    /** A change of the number of workers: at [tSec], the number became [workers]. */
    data class WorkerChange(val tSec: Int, val workers: Int)

    /**
     * The moments where the number of workers changed, from the samples in order. The first sample
     * with a number of workers starts the list; samples without one (0) are skipped.
     */
    fun workerChanges(samples: List<Sample>): List<WorkerChange> {
        val changes = ArrayList<WorkerChange>()
        var last = 0
        for (sample in samples) {
            if (sample.workers <= 0 || sample.workers == last) continue
            changes += WorkerChange(sample.tSec, sample.workers)
            last = sample.workers
        }
        return changes
    }

    /** "14:30" style label of a run started at [startMs] and marked at [tSec]. */
    fun clockLabel(startMs: Long, tSec: Int, zoneOffsetMs: Long = java.util.TimeZone.getDefault().getOffset(startMs).toLong()): String {
        val total = ((startMs + zoneOffsetMs) / 1000 + tSec) / 60
        val minutes = ((total % 1440) + 1440) % 1440
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    /** Largest value of the memory axis, rounded up to a multiple of 200 Mo, at least 200. */
    fun memoryAxisMax(peakMb: Int): Int = maxOf(200, ((peakMb + 199) / 200) * 200)
}
