package com.example.chatlab

/** Counts the analysed photos by their real type (read from the bytes, not the name). */
object TypeCounts {
    fun label(mime: String): String = when (mime) {
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "image/webp" -> "WebP"
        else -> "Autre"
    }

    fun of(mimes: List<String>): Map<String, Int> =
        mimes.groupingBy { label(it) }.eachCount().toSortedMap()
}
