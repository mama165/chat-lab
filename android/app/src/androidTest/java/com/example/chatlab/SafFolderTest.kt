package com.example.chatlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reading a folder chosen by the user, through the same path as the real picker
 * (DocumentsContract): a test documents provider serves real images, a refused folder,
 * and a file that cannot be opened.
 */
@RunWith(AndroidJUnit4::class)
class SafFolderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "saf-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        TestDocumentsProvider.reset()
        TestDocumentsProvider.base = root
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.reset()
        root.deleteRecursively()
    }

    private fun folder(name: String = "Camera") =
        SourceFolder(TestDocumentsProvider.treeUri().toString(), name)

    @Test
    fun aReadableFolderIsListedByTheBytesOfItsFiles() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg())
        TestFiles.write(root, "sous/b.png", TestFiles.png())
        TestFiles.write(root, "renomme.dat", TestFiles.webp())
        TestFiles.write(root, "doc.pdf", TestFiles.pdf())
        val status = FolderCheck.check(context, folder())
        assertEquals(FolderStatus.State.READABLE, status.state)
        assertEquals(3, status.photos)
        assertEquals(1, status.ignored)
        assertTrue(status.refused.isEmpty())
    }

    @Test
    fun aFolderWithoutImagesIsEmpty() {
        TestFiles.write(root, "note.txt", TestFiles.text())
        val status = FolderCheck.check(context, folder())
        assertEquals(FolderStatus.State.EMPTY, status.state)
        assertEquals(1, status.ignored)
    }

    @Test
    fun aRefusedSubFolderIsNamedAndTheRestIsStillRead() {
        TestFiles.write(root, "visible.jpg", TestFiles.jpeg())
        TestFiles.write(root, "Privé/cache.jpg", TestFiles.jpeg())
        TestDocumentsProvider.refused += "${TestDocumentsProvider.ROOT}/Privé"
        val status = FolderCheck.check(context, folder())
        assertEquals(FolderStatus.State.READABLE, status.state)
        assertEquals(1, status.photos)
        assertEquals(listOf("Privé"), status.refused)
    }

    @Test
    fun aRefusedRootIsRefused() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg())
        TestDocumentsProvider.refused += TestDocumentsProvider.ROOT
        assertEquals(FolderStatus.State.REFUSED, FolderCheck.check(context, folder()).state)
    }

    @Test
    fun scanGivesFilesThatOpenWithTheirRealContent() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg(40, 30))
        val result = FolderScanner.scan(SafDir.root(context, folder()))
        val candidate = result.candidates.single()
        assertEquals(FileSniffer.JPEG, candidate.mime)
        val bytes = candidate.file.open()?.use { it.readBytes() }
        assertNotNull(bytes)
        assertEquals(File(root, "a.jpg").length(), bytes!!.size.toLong())
    }

    @Test
    fun aFileThatCannotBeOpenedIsInaccessible() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg())
        TestDocumentsProvider.unreadable += "${TestDocumentsProvider.ROOT}/a.jpg"
        val result = FolderScanner.scan(SafDir.root(context, folder()))
        assertTrue(result.candidates.isEmpty())
        assertEquals(listOf(FailureReason.INACCESSIBLE), result.unreadable.map { it.reason })
    }

    @Test
    fun theFileIdsAreStableBetweenTwoScans() {
        TestFiles.write(root, "a.jpg", TestFiles.jpeg())
        TestFiles.write(root, "b.png", TestFiles.png())
        val first = FolderScanner.scan(SafDir.root(context, folder())).candidates.map { it.file.id }.sorted()
        val second = FolderScanner.scan(SafDir.root(context, folder())).candidates.map { it.file.id }.sorted()
        assertEquals(first, second)
        assertEquals(2, first.toSet().size)
    }
}
