package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScaleTest {
    @Test
    fun aNormalPhotoIsNotReduced() {
        assertEquals(1, ImageScale.sampleSize(4032, 3024))
        assertEquals(1, ImageScale.sampleSize(1080, 1920))
        assertEquals(1, ImageScale.sampleSize(4096, 100))
    }

    @Test
    fun aHugeImageIsReducedByPowersOfTwo() {
        assertEquals(2, ImageScale.sampleSize(8000, 6000))
        assertEquals(4, ImageScale.sampleSize(12000, 9000))
        assertEquals(8, ImageScale.sampleSize(30000, 20000))
    }

    @Test
    fun theReducedImageNeverExceedsTheLimit() {
        for (side in listOf(4097, 5000, 8192, 8193, 16000, 40000)) {
            val sample = ImageScale.sampleSize(side, side / 2)
            assertTrue("côté $side", side / sample <= ImageScale.MAX_SIDE)
            assertTrue("puissance de 2 pour $side", sample and (sample - 1) == 0)
        }
    }

    @Test
    fun theLongestSideDecides() {
        assertEquals(ImageScale.sampleSize(9000, 100), ImageScale.sampleSize(100, 9000))
    }

    @Test
    fun aCustomLimitIsHonoured() {
        assertEquals(4, ImageScale.sampleSize(4000, 3000, maxSide = 1000))
        assertEquals(1, ImageScale.sampleSize(800, 600, maxSide = 1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLimitOfZeroIsRejected() {
        ImageScale.sampleSize(100, 100, maxSide = 0)
    }
}
