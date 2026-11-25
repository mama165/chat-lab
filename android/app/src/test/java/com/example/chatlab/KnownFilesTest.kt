package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class KnownFilesTest {
    private fun entry(id: String, name: String, size: Long, modified: Long) = JSONObject()
        .put("id", id).put("name", name).put("size", size).put("modified", modified)

    @Test
    fun anEntryWithoutNameOrDateHasNoIdentity() {
        assertNull(KnownFiles.identityOf(JSONObject().put("id", "x")))
    }

    @Test
    fun theSameFileIsFoundByItsIdentityWhenItsAddressChanged() {
        val old = entry("content://old/IMG_1.jpg", "IMG_1.jpg", 1200, 1000)
        val byId = mapOf("content://old/IMG_1.jpg" to old)
        val byIdentity = KnownFiles.byIdentity(listOf(old))
        val found = KnownFiles.find(byId, byIdentity, "/storage/emulated/0/DCIM/IMG_1.jpg", "IMG_1.jpg", 1200, 1000)
        assertSame(old, found)
    }

    @Test
    fun aChangedFileIsNotFoundByIdentity() {
        val old = entry("content://old/IMG_1.jpg", "IMG_1.jpg", 1200, 1000)
        val byIdentity = KnownFiles.byIdentity(listOf(old))
        assertNull(KnownFiles.find(emptyMap(), byIdentity, "/x", "IMG_1.jpg", 1300, 1000))
        assertNull(KnownFiles.find(emptyMap(), byIdentity, "/x", "IMG_1.jpg", 1200, 2000))
    }

    @Test
    fun anAddressMatchWinsOverTheIdentity() {
        val byAddress = entry("/a", "IMG.jpg", 10, 1)
        val byIdentityOnly = entry("/b", "IMG.jpg", 10, 1)
        val found = KnownFiles.find(mapOf("/a" to byAddress), KnownFiles.byIdentity(listOf(byIdentityOnly)), "/a", "IMG.jpg", 10, 1)
        assertSame(byAddress, found)
    }

    @Test
    fun aFileWithAnotherNameIsUnknown() {
        val old = entry("/a", "IMG.jpg", 10, 1)
        assertNull(KnownFiles.find(emptyMap(), KnownFiles.byIdentity(listOf(old)), "/b", "AUTRE.jpg", 10, 1))
        assertEquals(2, KnownFiles.byIdentity(listOf(old, entry("/c", "AUTRE.jpg", 5, 2))).size)
    }
}
