package com.example.chatlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** De vraies images sur le vrai système de fichiers d'Android : ce qui est pris, ce qui ne l'est pas, et pourquoi. */
@RunWith(AndroidJUnit4::class)
class ImageFilesTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "images-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.walkTopDown().forEach { it.setReadable(true); it.setExecutable(true) }
        root.deleteRecursively()
    }

    private fun scan() = FolderScanner.scan(JavaDirNode(root))

    @Test
    fun realJpegPngAndWebpAreTakenWhateverTheirName() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg())
        TestFiles.write(root, "b.png", TestFiles.png())
        TestFiles.write(root, "c.webp", TestFiles.webp())
        TestFiles.write(root, "sans_extension", TestFiles.jpeg())
        TestFiles.write(root, "mauvaise.txt", TestFiles.png())
        val result = scan()
        assertEquals(
            mapOf(
                "a.jpg" to FileSniffer.JPEG, "b.png" to FileSniffer.PNG, "c.webp" to FileSniffer.WEBP,
                "sans_extension" to FileSniffer.JPEG, "mauvaise.txt" to FileSniffer.PNG,
            ),
            result.candidates.associate { it.file.name to it.mime },
        )
        assertTrue(result.ignored.isEmpty() && result.unreadable.isEmpty())
    }

    @Test
    fun otherTypesAreIgnoredAndCounted() {
        TestFiles.write(root, "anim.gif", TestFiles.gif())
        TestFiles.write(root, "doc.pdf", TestFiles.pdf())
        TestFiles.write(root, "note.jpg", TestFiles.text())
        val result = scan()
        assertTrue(result.candidates.isEmpty())
        assertEquals(
            mapOf("anim.gif" to FileSniffer.GIF, "doc.pdf" to FileSniffer.PDF, "note.jpg" to null),
            result.ignored.associate { it.name to it.mime },
        )
    }

    @Test
    fun anEmptyFileIsReportedEmpty() {
        TestFiles.write(root, "vide.jpg", ByteArray(0))
        val result = scan()
        assertEquals(listOf(FailureReason.EMPTY), result.unreadable.map { it.reason })
    }

    @Test
    fun aFileWithoutReadPermissionIsInaccessible() {
        val file = TestFiles.write(root, "interdit.jpg", TestFiles.jpeg())
        assertTrue(file.setReadable(false, false))
        val result = scan()
        assertEquals(listOf(UnreadableFile("interdit.jpg", FailureReason.INACCESSIBLE, root.name)), result.unreadable)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun aFolderWithoutReadPermissionIsRefusedAndTheRestStillRead() {
        TestFiles.write(root, "visible.jpg", TestFiles.jpeg())
        val secret = File(root, "Privé").also { it.mkdirs() }
        TestFiles.write(secret, "cache.jpg", TestFiles.jpeg())
        assertTrue(secret.setReadable(false, false))
        val result = scan()
        assertEquals(listOf("visible.jpg"), result.candidates.map { it.file.name })
        assertEquals(listOf("Privé"), result.refused)
    }

    @Test
    fun loadingARealImageGivesItsPixels() {
        val file = TestFiles.write(root, "a.jpg", TestFiles.jpeg(80, 60))
        assertEquals(80 to 60, ImageLoader.bounds(file))
        val bitmap = ImageLoader.decode(file)
        assertEquals(80, bitmap.width)
        assertEquals(60, bitmap.height)
    }

    @Test
    fun loadingPngAndWebpWorksToo() {
        val png = ImageLoader.decode(TestFiles.write(root, "a.png", TestFiles.png(50, 40)))
        assertEquals(50 to 40, png.width to png.height)
        val webp = ImageLoader.decode(TestFiles.write(root, "a.webp", TestFiles.webp(50, 40)))
        assertEquals(50 to 40, webp.width to webp.height)
    }

    @Test
    fun aVeryLargeImageIsReducedNotRefused() {
        val file = TestFiles.write(root, "grande.jpg", TestFiles.hugeJpeg(6000, 4500))
        assertEquals(6000 to 4500, ImageLoader.bounds(file))
        val bitmap = ImageLoader.decode(file)
        assertTrue("le plus grand côté doit tenir sous ${ImageScale.MAX_SIDE}", maxOf(bitmap.width, bitmap.height) <= ImageScale.MAX_SIDE)
        assertTrue("l'image ne doit pas être trop réduite", maxOf(bitmap.width, bitmap.height) >= ImageScale.MAX_SIDE / 2)
        assertEquals(6000.toDouble() / 4500, bitmap.width.toDouble() / bitmap.height, 0.02)
    }

    @Test
    fun aSmallLimitReducesMore() {
        val file = TestFiles.write(root, "a.jpg", TestFiles.jpeg(800, 600))
        val bitmap = ImageLoader.decode(file, maxSide = 200)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= 200)
    }

    @Test
    fun anImageThatIsNotOneIsUnreadable() {
        val file = TestFiles.write(root, "faux.jpg", TestFiles.fakeJpeg())
        // Les octets annoncent un JPEG : le listage le garde...
        assertEquals(FileSniffer.JPEG, scan().candidates.single().mime)
        // ... mais le décodage échoue, avec la raison « image illisible ».
        assertNull(ImageLoader.bounds(file))
        val failure = runCatching { ImageLoader.decode(file) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(FailureReason.UNREADABLE, Failures.reasonOf(failure!!))
    }

    @Test
    fun aMissingFileGivesNoBounds() {
        assertNull(ImageLoader.bounds(File(root, "absent.jpg")))
    }

    @Test
    fun theSnifferAgreesWithAndroidsOwnDecoder() {
        // Chaque image réelle reconnue par ses octets est aussi décodable par Android.
        val files = listOf(
            TestFiles.write(root, "1.jpg", TestFiles.jpeg()),
            TestFiles.write(root, "2.png", TestFiles.png()),
            TestFiles.write(root, "3.webp", TestFiles.webp()),
        )
        for (file in files) {
            val head = file.inputStream().use { it.readNBytes(FileSniffer.HEAD_SIZE) }
            assertTrue(file.name, FileSniffer.isSupported(FileSniffer.detect(head)))
            assertNotNull(file.name, ImageLoader.bounds(file))
        }
    }

    @Test
    fun aMixedFolderGivesTheExpectedCounts() {
        TestFiles.write(root, "ok1.jpg", TestFiles.jpeg())
        TestFiles.write(root, "ok2.png", TestFiles.png())
        TestFiles.write(root, "sous/ok3.webp", TestFiles.webp())
        TestFiles.write(root, "note.txt", TestFiles.text())
        TestFiles.write(root, "doc.pdf", TestFiles.pdf())
        TestFiles.write(root, "vide.jpg", ByteArray(0))
        val result = scan()
        assertEquals(3, result.candidates.size)
        assertEquals(2, result.ignored.size)
        assertEquals(1, result.unreadable.size)
        assertTrue(result.refused.isEmpty())
    }
}
