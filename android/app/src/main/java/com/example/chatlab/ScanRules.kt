package com.example.chatlab

import java.io.FileNotFoundException
import java.io.IOException

/** Why a file could not be read during the analysis. The labels are the ones of the screen. */
enum class FailureReason(val label: String) {
    /** Android refuses to open the file, or it disappeared between the listing and the reading. */
    INACCESSIBLE("fichier inaccessible"),

    /** The file opens but it is not an image we can decode. */
    UNREADABLE("image illisible"),

    /** The file contains no bytes. */
    EMPTY("fichier vide"),

    /** The file can be read, but the analysis itself failed: a problem of the application. */
    ANALYSIS("erreur d'analyse"),
}

/** A failure whose cause is known: the reason shown comes directly from [reason]. */
class ImageFailure(val reason: FailureReason, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: reason.label, cause)

object Failures {
    /** Classifies an error under one of the displayable reasons. */
    fun reasonOf(error: Throwable): FailureReason = when (error) {
        is ImageFailure -> error.reason
        is FileNotFoundException, is SecurityException -> FailureReason.INACCESSIBLE
        is IOException -> FailureReason.INACCESSIBLE
        // Insufficient memory when decoding: the file is not readable as it is.
        is OutOfMemoryError -> FailureReason.UNREADABLE
        else -> FailureReason.ANALYSIS
    }
}

/** What the analysis does with a file, decided before opening it fully. */
sealed interface ScanDecision {
    /** An image we can read: it will be analysed. */
    data class Analyze(val mime: String) : ScanDecision

    /** Not an image we can read: dropped, but counted. [mime] is null if the type is unknown.nconnu. */
    data class Ignore(val mime: String?) : ScanDecision

    /** Impossible to decide or to read: reported to the user with its reason. */
    data class Unreadable(val reason: FailureReason) : ScanDecision
}

object ScanRules {
    /**
     * Decides the fate of a file from its size and its first bytes.
     * [head] is null when the file could not be opened.
     */
    fun classify(size: Long, head: ByteArray?): ScanDecision {
        if (head == null) return ScanDecision.Unreadable(FailureReason.INACCESSIBLE)
        if (size == 0L || head.isEmpty()) return ScanDecision.Unreadable(FailureReason.EMPTY)
        val mime = FileSniffer.detect(head)
        return if (FileSniffer.isSupported(mime)) ScanDecision.Analyze(mime!!) else ScanDecision.Ignore(mime)
    }
}

/** Reduction computation when reading a very large image, without loading it entirely. */
object ImageScale {
    /** Largest side accepted after decoding: beyond it, the image is read reduced. */
    const val MAX_SIDE = 4096

    /**
     * The reduction factor (power of 2, like `inSampleSize`) so that the largest side
     * does not exceed [maxSide]. An image already small enough is not reduced.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        require(maxSide > 0) { "maxSide doit être positif" }
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / sample > maxSide) sample *= 2
        return sample
    }
}
