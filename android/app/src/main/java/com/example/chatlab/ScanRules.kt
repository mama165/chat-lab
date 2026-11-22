package com.example.chatlab

import java.io.FileNotFoundException
import java.io.IOException

/** Pourquoi un fichier n'a pas pu être lu pendant l'analyse. Les libellés sont ceux de l'écran. */
enum class FailureReason(val label: String) {
    /** Android refuse d'ouvrir le fichier, ou il a disparu entre le listage et la lecture. */
    INACCESSIBLE("fichier inaccessible"),

    /** Le fichier s'ouvre mais ce n'est pas une image qu'on peut décoder. */
    UNREADABLE("image illisible"),

    /** Le fichier ne contient aucun octet. */
    EMPTY("fichier vide"),

    /** Le fichier se lit, mais l'analyse elle-même a échoué : un problème de l'application. */
    ANALYSIS("erreur d'analyse"),
}

/** Un échec dont la cause est connue : la raison affichée vient directement de [reason]. */
class ImageFailure(val reason: FailureReason, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: reason.label, cause)

object Failures {
    /** Classe une erreur dans l'une des raisons affichables. */
    fun reasonOf(error: Throwable): FailureReason = when (error) {
        is ImageFailure -> error.reason
        is FileNotFoundException, is SecurityException -> FailureReason.INACCESSIBLE
        is IOException -> FailureReason.INACCESSIBLE
        // Une mémoire insuffisante au décodage : le fichier n'est pas lisible tel quel.
        is OutOfMemoryError -> FailureReason.UNREADABLE
        else -> FailureReason.ANALYSIS
    }
}

/** Ce que l'analyse fait d'un fichier, décidé avant de l'ouvrir en entier. */
sealed interface ScanDecision {
    /** Une image qu'on sait lire : elle sera analysée. */
    data class Analyze(val mime: String) : ScanDecision

    /** Pas une image qu'on sait lire : écartée, mais comptée. [mime] est null si le type est inconnu. */
    data class Ignore(val mime: String?) : ScanDecision

    /** Impossible de décider ou de lire : signalé à l'utilisateur avec sa raison. */
    data class Unreadable(val reason: FailureReason) : ScanDecision
}

object ScanRules {
    /**
     * Décide du sort d'un fichier d'après sa taille et ses premiers octets.
     * [head] vaut null quand le fichier n'a pas pu être ouvert.
     */
    fun classify(size: Long, head: ByteArray?): ScanDecision {
        if (head == null) return ScanDecision.Unreadable(FailureReason.INACCESSIBLE)
        if (size == 0L || head.isEmpty()) return ScanDecision.Unreadable(FailureReason.EMPTY)
        val mime = FileSniffer.detect(head)
        return if (FileSniffer.isSupported(mime)) ScanDecision.Analyze(mime!!) else ScanDecision.Ignore(mime)
    }
}

/** Calcul de réduction à la lecture d'une très grande image, sans la charger en entier. */
object ImageScale {
    /** Plus grand côté accepté après décodage : au-delà, l'image est lue en réduction. */
    const val MAX_SIDE = 4096

    /**
     * Le facteur de réduction (puissance de 2, comme `inSampleSize`) pour que le plus grand côté
     * ne dépasse pas [maxSide]. Une image déjà assez petite n'est pas réduite.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        require(maxSide > 0) { "maxSide doit être positif" }
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / sample > maxSide) sample *= 2
        return sample
    }
}
