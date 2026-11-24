package com.example.chatlab

/**
 * Measures of the native memory (the memory of the ONNX models), for the diagnostics. The
 * numbers are kept in the run's journal, to see what each model and each photo takes.
 */
object NativeMemory {
    private const val MB = 1024L * 1024L

    /** The photos after which the memory is written to the journal: the first ones, then a few more. */
    val PHOTO_POINTS: Set<Int> = setOf(1, 2, 10, 30, 60)

    /** Bytes to megabytes, rounded down. */
    fun mb(bytes: Long): Long = bytes / MB

    /** The memory added by a step, in megabytes; never negative (a step can free memory too). */
    fun deltaMb(beforeBytes: Long, afterBytes: Long): Long = ((afterBytes - beforeBytes) / MB).coerceAtLeast(0L)

    /** Whether the memory is written for the photo that is the [count]-th of the run. */
    fun isPhotoPoint(count: Int): Boolean = count in PHOTO_POINTS
}
