package com.example.chatlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Reads an image from a file. A very large image is read reduced, so as not to run out of
 * memory: no image is "too large", it is simply reduced. An image we cannot
 * decode throws [ImageFailure] with the reason "image illisible".
 */
object ImageLoader {
    /** The dimensions of the image without decoding it, or null if the file is not a readable image. */
    fun bounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** Décode l'image, réduite si son plus grand côté dépasse [maxSide]. */
    fun decode(file: File, maxSide: Int = ImageScale.MAX_SIDE): Bitmap {
        val (width, height) = bounds(file) ?: throw ImageFailure(FailureReason.UNREADABLE, "dimensions illisibles")
        var sample = ImageScale.sampleSize(width, height, maxSide)
        // Insufficient memory is not an error of the file: we try again, reducing more.
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
