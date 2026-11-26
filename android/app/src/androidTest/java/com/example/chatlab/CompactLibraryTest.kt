package com.example.chatlab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

/** The compact store on the phone: the photos written there are read back the same way. */
@RunWith(AndroidJUnit4::class)
class CompactLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Photo(override val id: String, override val name: String, override val length: Long, override val lastModified: Long) : FileNode {
        override fun open(): InputStream? = null
    }

    /** The store is opened and closed around each use (the store is not auto-closeable). */
    private fun <T> withStore(block: (CompactLibrary) -> T): T {
        val store = CompactLibrary(context)
        try {
            return block(store)
        } finally {
            store.close()
        }
    }

    @Before
    fun emptyStore() {
        context.deleteDatabase("photos.db")
        File(context.filesDir, "vectors.bin").delete()
    }

    private fun record(address: String, name: String, folder: String?, size: Long = 100, text: String? = null, takenMs: Long? = null) =
        PhotoRecord(
            address = address,
            name = name,
            path = address,
            folder = folder,
            mime = "image/jpeg",
            size = size,
            modified = 500L,
            taken = null,
            takenMs = takenMs,
            analysisVersion = ANALYSIS_VERSION,
            addedAt = 1_700_000_000_000L,
            faces = 1,
            portrait = false,
            indoor = true,
            text = text,
            vector = FloatArray(768) { it / 1000f },
        )

    @Test
    fun loadAllGivesEveryPhotoWithItsVector() {
        withStore { store ->
            store.add(listOf(record("/s/DCIM/a.jpg", "a.jpg", "DCIM", text = "facture"), record("/s/Pictures/b.png", "b.png", "Pictures")))
            val all = store.loadAll()
            assertEquals(2, all.size)
            val a = all.first { it.id == "/s/DCIM/a.jpg" }
            assertEquals("a.jpg", a.name)
            assertEquals("DCIM", a.folder)
            assertEquals("facture", a.text)
            assertEquals(true, a.indoor)
            assertNotNull(a.vector)
            assertEquals(768, a.vector!!.size)
            assertEquals(0.5f, a.vector!![500], 0.0001f)
        }
    }

    @Test
    fun recordsHaveTheFieldsOfTheLibraryFile() {
        withStore { store ->
            store.add(listOf(record("/s/DCIM/a.jpg", "a.jpg", "DCIM", size = 42, takenMs = 1_700_000_000_000L)))
            val items = mutableListOf<org.json.JSONObject>()
            store.recordItems { items += it }
            val item = items.single()
            assertEquals("/s/DCIM/a.jpg", item.getString("source"))
            assertEquals(ANALYSIS_VERSION, item.getInt("analysis_version"))
            assertEquals(42L, item.getLong("size"))
            assertEquals(500L, item.getLong("modified"))
            assertEquals("DCIM", item.getString("folder"))
            assertEquals("image/jpeg", item.getString("mime"))
            assertEquals(1_700_000_000_000L, item.getLong("taken_ms"))
            assertFalse("a photo without text has no text key", item.has("text"))
        }
    }

    @Test
    fun aKnownPhotoIsFoundByItsNameSizeAndDate() {
        withStore { it.add(listOf(record("/s/DCIM/a.jpg", "a.jpg", "DCIM", size = 42))) }
        val known = readKnownFromStore(context)
        assertNotNull(known.find(Photo("/other/address/a.jpg", "a.jpg", 42, 500)))
        assertEquals(null, known.find(Photo("/other/address/a.jpg", "a.jpg", 43, 500)))
    }

    @Test
    fun removedFoldersKeepOnlyTheFoldersNoLongerListed() {
        withStore {
            it.add(listOf(record("/s/DCIM/a.jpg", "a.jpg", "DCIM"), record("/s/old/c.jpg", "c.jpg", "old"), record("/s/none/d.jpg", "d.jpg", null)))
        }
        val kept = keepFromRemovedFolders(context, setOf("DCIM"))
        assertEquals(listOf("/s/old/c.jpg"), kept.map { it.getString("source") })
        assertTrue("the kept photo carries its vector", kept.single().has("vectors"))
    }
}
