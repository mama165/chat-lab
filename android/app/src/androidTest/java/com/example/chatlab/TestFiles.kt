package com.example.chatlab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/** De vraies images, créées sur l'émulateur, et de faux fichiers qui leur ressemblent. */
object TestFiles {
    private fun bitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.rgb(30, 60, 110); textSize = height / 5f }
        canvas.drawRect(width * 0.1f, height * 0.1f, width * 0.6f, height * 0.5f, paint)
        canvas.drawText("TEST", width * 0.1f, height * 0.9f, paint)
        return bitmap
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, out)) { "compression $format impossible" }
        bitmap.recycle()
        return out.toByteArray()
    }

    fun jpeg(width: Int = 64, height: Int = 48): ByteArray = encode(bitmap(width, height), Bitmap.CompressFormat.JPEG)

    fun png(width: Int = 64, height: Int = 48): ByteArray = encode(bitmap(width, height), Bitmap.CompressFormat.PNG)

    @Suppress("DEPRECATION")
    fun webp(width: Int = 64, height: Int = 48): ByteArray = encode(
        bitmap(width, height),
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP,
    )

    /** Une très grande image : en 565, six mille pixels de large tiennent en mémoire sur l'émulateur. */
    fun hugeJpeg(width: Int = 6000, height: Int = 4500): ByteArray =
        encode(bitmap(width, height, Bitmap.Config.RGB_565), Bitmap.CompressFormat.JPEG, 70)

    /** Les premiers octets d'un JPEG suivis de n'importe quoi : le fichier annonce une image qui n'en est pas une. */
    fun fakeJpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { (it * 31).toByte() }

    fun gif(): ByteArray = "GIF89a".toByteArray() + ByteArray(40)

    fun pdf(): ByteArray = "%PDF-1.4\n%".toByteArray() + ByteArray(40)

    fun text(): ByteArray = "Ceci est un texte, pas une image.".toByteArray()

    fun write(folder: File, name: String, bytes: ByteArray): File =
        File(folder, name).also {
            it.parentFile?.mkdirs()
            it.writeBytes(bytes)
        }
}
