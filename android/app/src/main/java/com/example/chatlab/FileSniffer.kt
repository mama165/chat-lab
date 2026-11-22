package com.example.chatlab

/**
 * Reconnaît le type d'un fichier d'après ses premiers octets (« magic bytes »), comme le scanner
 * Go (mimetype.Detect sur les 64 premiers octets). Le nom et l'extension ne comptent pas : un
 * JPEG renommé en .dat reste un JPEG, un texte renommé en .jpg n'en est pas un.
 */
object FileSniffer {
    /** Nombre d'octets lus au début du fichier, comme dans le scanner Go. */
    const val HEAD_SIZE = 64

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val GIF = "image/gif"
    const val HEIC = "image/heic"
    const val PDF = "application/pdf"

    /** Les types que l'analyse sait lire aujourd'hui. */
    val SUPPORTED: Set<String> = setOf(JPEG, PNG, WEBP)

    /** Le type MIME détecté, ou null si les octets ne correspondent à aucun format connu. */
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

    /** L'étiquette courte d'un type, pour l'écran des filtres : JPG, PNG, WebP… */
    fun label(mime: String): String = when (mime) {
        JPEG -> "JPG"
        PNG -> "PNG"
        WEBP -> "WebP"
        GIF -> "GIF"
        HEIC -> "HEIC"
        PDF -> "PDF"
        else -> mime.substringAfter('/').uppercase()
    }

    /** Le type MIME d'après l'étiquette d'un filtre : l'inverse de [label]. */
    fun mimeOf(label: String): String? = listOf(JPEG, PNG, WEBP, GIF, HEIC, PDF).firstOrNull { label(it) == label }

    private fun isJpeg(h: ByteArray) =
        h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()

    private fun isPng(h: ByteArray) = startsWith(h, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    private fun isGif(h: ByteArray) = startsWith(h, "GIF87a".toByteArray(Charsets.ISO_8859_1)) ||
        startsWith(h, "GIF89a".toByteArray(Charsets.ISO_8859_1))

    /** « RIFF », quatre octets de taille, puis « WEBP » : un WAV ou un AVI ne passent pas. */
    private fun isWebp(h: ByteArray) = h.size >= 12 &&
        startsWith(h, "RIFF".toByteArray(Charsets.ISO_8859_1)) &&
        h.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.ISO_8859_1))

    /** Les photos des téléphones récents : « ftyp » puis une marque heic, heix ou mif1. */
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
