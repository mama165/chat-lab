package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnalysisRecordFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Int = 10): File =
        File(folder.root, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun wipeErasesThePhotoTableAndTheVectors() {
        file("photos.db")
        file("photos.db-journal")
        file("vectors.bin")

        assertEquals(3, AnalysisRecordFiles.wipe(folder.root))
        assertFalse(File(folder.root, "photos.db").exists())
        assertFalse(File(folder.root, "vectors.bin").exists())
    }

    @Test
    fun wipeKeepsTheMigrationMarkerAndTheModels() {
        file("photos.db")
        file("photos.migrated")
        File(folder.root, "models").mkdir()
        file("models/text_model.onnx")

        AnalysisRecordFiles.wipe(folder.root)

        assertTrue(File(folder.root, "photos.migrated").exists())
        assertTrue(File(folder.root, "models/text_model.onnx").exists())
    }

    @Test
    fun sizeCountsOnlyTheRecordFiles() {
        file("photos.db", bytes = 100)
        file("vectors.bin", bytes = 50)
        file("photos.migrated", bytes = 999)

        assertEquals(150L, AnalysisRecordFiles.size(folder.root))
    }

    @Test
    fun wipeOfAnEmptyFolderDeletesNothing() {
        assertEquals(0, AnalysisRecordFiles.wipe(folder.root))
        assertEquals(0L, AnalysisRecordFiles.size(folder.root))
    }
}
