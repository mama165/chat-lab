package com.example.chatlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Lit une image d'un fichier. Une très grande image est lue en réduction, pour ne pas manquer de
 * mémoire : aucune image n'est « trop grande », elle est simplement réduite. Une image qu'on ne
 * peut pas décoder lève [ImageFailure] avec la raison « image illisible ».
 */
object ImageLoader {
    /** Les dimensions de l'image sans la décoder, ou null si le fichier n'est pas une image lisible. */
    fun bounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** Décode l'image, réduite si son plus grand côté dépasse [maxSide]. */
    fun decode(file: File, maxSide: Int = ImageScale.MAX_SIDE): Bitmap {
        val (width, height) = bounds(file) ?: throw ImageFailure(FailureReason.UNREADABLE, "dimensions illisibles")
        var sample = ImageScale.sampleSize(width, height, maxSide)
        // Une mémoire insuffisante n'est pas une erreur du fichier : on réessaie en réduisant davantage.
        repeat(3) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                return BitmapFactory.decodeFile(file.path, options)
                    ?: throw ImageFailure(FailureReason.UNREADABLE, "décodage impossible")
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        throw ImageFailure(FailureReason.UNREADABLE, "mémoire insuffisante")
    }
}
