package com.example.chatlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The count of photos in a folder relies on the content of the files (their first bytes),
 * never on their name or extension. These tests check it on real files.
 */
class PhotoCountingTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("count").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun jpegBytes(): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** A WebP: "RIFF", the size, then "WEBP" (the content does not need to be decodable). */
    private fun webpBytes(): ByteArray =
        "RIFF".toByteArray() + byteArrayOf(20, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(12)

    /** A HEIC: a "ftyp" box with the heic brand. Not supported by the analysis. */
    private fun heicBytes(): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(20)

    /** An MP4 video: "ftyp" with the mp42 brand. Not supported. */
    private fun mp4Bytes(): ByteArray = byteArrayOf(0, 0, 0, 24) + "ftypmp42".toByteArray() + ByteArray(20)

    private fun gifBytes(): ByteArray = "GIF89a".toByteArray() + ByteArray(40)

    private fun pdfBytes(): ByteArray = "%PDF-1.4\n%".toByteArray() + ByteArray(40)

    private fun write(path: String, bytes: ByteArray) {
        File(root, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }

    private fun scan() = FolderScanner.scan(JavaDirNode(root))

    @Test
    fun aJpegIsAPhotoWhateverItsName() {
        write("IMG_0001.jpg", jpegBytes())
        write("sans_extension", jpegBytes())
        write("renomme.dat", jpegBytes())
        assertEquals(3, scan().candidates.size)
    }

    @Test
    fun aPngAndAWebpArePhotosWhateverTheirName() {
        write("a.txt", pngBytes())
        write("b.jpg", webpBytes())
        assertEquals(2, scan().candidates.size)
    }

    @Test
    fun aTextRenamedJpgIsNotAPhoto() {
        write("note.jpg", "Ceci est un texte.".toByteArray())
        val result = scan()
        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.ignored.size)
    }

    @Test
    fun aHeicVideoGifAndPdfAreNotPhotosAndAreCountedAsIgnored() {
        write("photo.heic", heicBytes())
        write("video.mp4", mp4Bytes())
        write("anim.gif", gifBytes())
        write("doc.pdf", pdfBytes())
        val result = scan()
        assertTrue(result.candidates.isEmpty())
        assertEquals(4, result.ignored.size)
    }

    @Test
    fun aFolderWithOnlyVideosHasNoPhotoButItsFilesAreKnown() {
        write("Vacances/clip1.mp4", mp4Bytes())
        write("Vacances/clip2.mp4", mp4Bytes())
        val counts = FolderCounts.countByFolder(scan().candidates.map { it.file.id }, root.absolutePath)
        assertEquals(null, counts[root.absolutePath])
        assertEquals(2, FileTally.count(JavaDirNode(root)))
    }

    @Test
    fun thePhotosAreCountedInTheirFolderAndAbove() {
        write("Camera/a.jpg", jpegBytes())
        write("Camera/b.png", pngBytes())
        write("Camera/video.mp4", mp4Bytes())
        write("Camera/Sous/c.jpg", jpegBytes())
        write("Download/doc.pdf", pdfBytes())
        val paths = scan().candidates.map { it.file.id }
        val counts = FolderCounts.countByFolder(paths, root.absolutePath)
        assertEquals(3, counts[File(root, "Camera").absolutePath])
        assertEquals(1, counts[File(root, "Camera/Sous").absolutePath])
        assertEquals(3, counts[root.absolutePath])
        assertEquals(null, counts[File(root, "Download").absolutePath])
    }

    @Test
    fun aMisnamedFileCountsByItsContent() {
        write("Camera/IMG.txt", jpegBytes())
        write("Camera/IMG.jpg", "pas une image".toByteArray())
        val photos = scan().candidates.map { it.file.name }
        assertEquals(listOf("IMG.txt"), photos)
    }
}
