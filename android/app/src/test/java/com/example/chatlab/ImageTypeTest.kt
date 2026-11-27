package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTypeTest {
    @Test
    fun aJpegIsOpenedAsAJpeg() {
        assertEquals("image/jpeg", imageMimeOf("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_0001.JPG"))
        assertEquals("image/jpeg", imageMimeOf("content://x/document/photo.jpeg"))
    }

    @Test
    fun otherPhotoFormatsKeepTheirOwnType() {
        assertEquals("image/png", imageMimeOf("content://x/document/capture.png"))
        assertEquals("image/heic", imageMimeOf("content://x/document/IMG.HEIC"))
        assertEquals("image/webp", imageMimeOf("content://x/document/a.webp"))
    }

    @Test
    fun anUnknownNameFallsBackToAnyImage() {
        assertEquals("image/*", imageMimeOf("content://x/document/no-extension"))
        assertEquals("image/*", imageMimeOf(""))
    }
}
