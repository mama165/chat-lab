package com.example.chatlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import javax.imageio.ImageIO

/** Folder walks on real files: images created by ImageIO, fake files, empty folderses. */
class FolderScannerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scan").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun image(name: String, format: String, width: Int = 40, height: Int = 30) {
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) bitmap.setRGB(x, y, (x * 5) shl 16 or (y * 7))
        val file = File(root, name).also { it.parentFile.mkdirs() }
        assertTrue("écriture $format", ImageIO.write(bitmap, format, file))
    }

    private fun raw(name: String, bytes: ByteArray) {
        File(root, name).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }

    private fun scan(): ScanResult = FolderScanner.scan(JavaDirNode(root))

    @Test
    fun realJpegAndPngAreAnalysed() {
        image("a.jpg", "jpg")
        image("b.png", "png")
        val result = scan()
        assertEquals(setOf("a.jpg", "b.png"), result.candidates.map { it.file.name }.toSet())
        assertEquals(FileSniffer.JPEG, result.candidates.first { it.file.name == "a.jpg" }.mime)
        assertEquals(FileSniffer.PNG, result.candidates.first { it.file.name == "b.png" }.mime)
        assertTrue(result.ignored.isEmpty() && result.unreadable.isEmpty())
    }

    @Test
    fun aJpegRenamedWithoutExtensionIsStillAnImage() {
        image("photo.jpg", "jpg")
        File(root, "photo.jpg").renameTo(File(root, "IMG_0001"))
        File(root, "IMG_0001").renameTo(File(root, "photo.dat"))
        val result = scan()
        assertEquals(listOf("photo.dat"), result.candidates.map { it.file.name })
        assertEquals(FileSniffer.JPEG, result.candidates.single().mime)
    }

    @Test
    fun aTextFileNamedJpgIsIgnoredNotAnalysed() {
        raw("faux.jpg", "ceci n'est pas une image".toByteArray())
        val result = scan()
        assertTrue(result.candidates.isEmpty())
        assertEquals(listOf("faux.jpg"), result.ignored.map { it.name })
        assertEquals(null, result.ignored.single().mime)
    }

    @Test
    fun aPngNamedJpgIsAnalysedAsPng() {
        image("trompeur.jpg", "png")
        val result = scan()
        assertEquals(FileSniffer.PNG, result.candidates.single().mime)
    }

    @Test
    fun webpIsRecognisedFromItsBytes() {
        raw("w.webp", "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(20))
        assertEquals(FileSniffer.WEBP, scan().candidates.single().mime)
    }

    @Test
    fun gifPdfAndHeicAreIgnoredWithTheirType() {
        raw("a.gif", "GIF89a".toByteArray() + ByteArray(20))
        raw("b.pdf", "%PDF-1.4\n".toByteArray() + ByteArray(20))
        raw("c.heic", byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(20))
        val result = scan()
        assertTrue(result.candidates.isEmpty())
        assertEquals(
            mapOf("a.gif" to FileSniffer.GIF, "b.pdf" to FileSniffer.PDF, "c.heic" to FileSniffer.HEIC),
            result.ignored.associate { it.name to it.mime },
        )
    }

    @Test
    fun anEmptyFileIsUnreadable() {
        raw("vide.jpg", ByteArray(0))
        val result = scan()
        assertEquals(listOf(UnreadableFile("vide.jpg", FailureReason.EMPTY, root.name)), result.unreadable)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun subfoldersAreVisited() {
        image("racine.jpg", "jpg")
        image("vacances/ete/plage.png", "png")
        val result = scan()
        assertEquals(setOf("racine.jpg", "plage.png"), result.candidates.map { it.file.name }.toSet())
    }

    @Test
    fun anEmptyFolderGivesAnEmptyResult() {
        val result = scan()
        assertTrue(result.isEmpty)
        assertFalse(result.rootRefused)
    }

    @Test
    fun everyCandidateKeepsItsFolderName() {
        image("a.jpg", "jpg")
        assertEquals(root.name, scan().candidates.single().folder)
    }

    @Test
    fun aKnownFileIsNotReadAgain() {
        image("connu.jpg", "jpg")
        var opened = 0
        val node = object : DirNode {
            override val name = "dossier"
            override fun children(): List<Any> = listOf(object : FileNode {
                override val name = "connu.jpg"
                override val length = 100L
                override val lastModified = 1L
                override val id = "x"
                override fun open(): InputStream? { opened++; return null }
            })
        }
        val result = FolderScanner.scan(node, reusable = { FileSniffer.JPEG })
        assertEquals(0, opened)
        assertEquals(FileSniffer.JPEG, result.candidates.single().mime)
    }

    private class FakeFile(
        override val name: String,
        override val length: Long = 100,
        private val opener: () -> InputStream?,
    ) : FileNode {
        override val lastModified = 1L
        override val id = "fake://$name"
        override fun open(): InputStream? = opener()
    }

    private class FakeDir(override val name: String, private val items: () -> List<Any>?) : DirNode {
        override fun children(): List<Any>? = items()
    }

    @Test
    fun aRefusedRootIsReportedAsSuch() {
        val result = FolderScanner.scan(FakeDir("Privé") { null })
        assertTrue(result.rootRefused)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun aRefusedSubfolderIsListedAndTheRestIsStillRead() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val tree = FakeDir("Camera") {
            listOf(
                FakeFile("ok.jpg") { jpeg.inputStream() },
                FakeDir("Privé") { null },
                FakeDir("Voyage") { listOf(FakeDir("Secret") { null }, FakeFile("v.jpg") { jpeg.inputStream() }) },
            )
        }
        val result = FolderScanner.scan(tree)
        assertEquals(setOf("ok.jpg", "v.jpg"), result.candidates.map { it.file.name }.toSet())
        assertEquals(listOf("Privé", "Voyage/Secret"), result.refused)
        assertFalse(result.rootRefused)
    }

    @Test
    fun aSubfolderThatThrowsSecurityExceptionIsRefused() {
        val tree = FakeDir("Camera") { listOf(FakeDir("Bloqué") { throw SecurityException("non") }) }
        assertEquals(listOf("Bloqué"), FolderScanner.scan(tree).refused)
    }

    @Test
    fun filesThatCannotBeOpenedAreInaccessible() {
        val tree = FakeDir("Drive") {
            listOf(
                FakeFile("ferme.jpg") { null },
                FakeFile("erreur.jpg") { throw IOException("réseau") },
                FakeFile("refus.jpg") { throw SecurityException("droits") },
            )
        }
        val result = FolderScanner.scan(tree)
        assertEquals(setOf("ferme.jpg", "erreur.jpg", "refus.jpg"), result.unreadable.map { it.name }.toSet())
        assertTrue(result.unreadable.all { it.reason == FailureReason.INACCESSIBLE })
    }

    @Test
    fun aShortReadStillClassifiesTheFile() {
        // A stream that returns the bytes only in small pieces is still read in full.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val slow = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < jpeg.size) jpeg[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= jpeg.size) return -1
                b[off] = jpeg[i++]
                return 1
            }
        }
        val tree = FakeDir("lent") { listOf(FakeFile("lent.jpg") { slow }) }
        assertEquals(FileSniffer.JPEG, FolderScanner.scan(tree).candidates.single().mime)
    }

    @Test
    fun resultsAreMixedCorrectlyInOneFolder() {
        image("bonne.jpg", "jpg")
        image("bonne2.png", "png")
        raw("note.txt", "texte".toByteArray())
        raw("vide.png", ByteArray(0))
        raw("doc.pdf", "%PDF-1.4".toByteArray() + ByteArray(10))
        val result = scan()
        assertEquals(2, result.candidates.size)
        assertEquals(2, result.ignored.size)
        assertEquals(1, result.unreadable.size)
        assertEquals(0, result.refused.size)
    }

    @Test
    fun theExaminedCountGrowsWithEveryFileAndEndsAtTheTotal() {
        image("a.png", "png")
        raw("sous/b.txt", "texte".toByteArray())
        raw("sous/c.jpg", ByteArray(0))
        val seen = ArrayList<Int>()
        FolderScanner.scan(JavaDirNode(root), examined = { seen += it })
        assertEquals(listOf(1, 2, 3), seen)
    }
}
