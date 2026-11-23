package com.example.chatlab

/**
 * Recognises the type of a file from its first bytes ("magic bytes"), like the scanner
 * Go (mimetype.Detect on the first 64 bytes). The name and extension do not count: a
 * JPEG renamed to .dat stays a JPEG, a text renamed to .jpg is not one.
 */
object FileSniffer {
    /** Number of bytes read at the start of the file, as in the Go scanner. */
    const val HEAD_SIZE = 64

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val GIF = "image/gif"
    const val HEIC = "image/heic"
    const val PDF = "application/pdf"

    /** The types the analysis can read today. */
    val SUPPORTED: Set<String> = setOf(JPEG, PNG, WEBP)

    /** The MIME type detected, or null if the bytes match no known format. */
    fun detect(head: ByteArray): String? = when {
        isJpeg(head) -> JPEG
        isPng(head) -> PNG
        isWebp(head) -> WEBP
        isGif(head) -> GIF
        isHeic(head) -> HEIC
        isPdf(head) -> PDF
        else -> null
    }

    fun isSupported(mime: String?): Boolean = mime != null && mime in SUPPORTED

    /** The short label of a type, for the filters screen: JPG, PNG, WebP… */
    fun label(mime: String): String = when (mime) {
        JPEG -> "JPG"
        PNG -> "PNG"
        WEBP -> "WebP"
        GIF -> "GIF"
        HEIC -> "HEIC"
        PDF -> "PDF"
        else -> mime.substringAfter('/').uppercase()
    }

    /** The MIME type from the label of a filter: the inverse of [label]. */
    fun mimeOf(label: String): String? = listOf(JPEG, PNG, WEBP, GIF, HEIC, PDF).firstOrNull { label(it) == label }

    private fun isJpeg(h: ByteArray) =
        h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()

    private fun isPng(h: ByteArray) = startsWith(h, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    private fun isGif(h: ByteArray) = startsWith(h, "GIF87a".toByteArray(Charsets.ISO_8859_1)) ||
        startsWith(h, "GIF89a".toByteArray(Charsets.ISO_8859_1))

    /** "RIFF", four bytes of size, then "WEBP": a WAV or an AVI does not pass. */
    private fun isWebp(h: ByteArray) = h.size >= 12 &&
        startsWith(h, "RIFF".toByteArray(Charsets.ISO_8859_1)) &&
        h.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.ISO_8859_1))

    /** The photos of recent phones: "ftyp" then a heic, heix or mif1 brand. */
    private fun isHeic(h: ByteArray): Boolean {
        if (h.size < 12) return false
        if (!h.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.ISO_8859_1))) return false
        val brand = String(h, 8, 4, Charsets.ISO_8859_1)
        return brand in setOf("heic", "heix", "hevc", "mif1", "msf1")
    }

    private fun isPdf(h: ByteArray) = startsWith(h, "%PDF-".toByteArray(Charsets.ISO_8859_1))

    private fun startsWith(h: ByteArray, prefix: ByteArray): Boolean {
        if (h.size < prefix.size) return false
        for (i in prefix.indices) if (h[i] != prefix[i]) return false
        return true
    }
}
