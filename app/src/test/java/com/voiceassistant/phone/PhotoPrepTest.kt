package com.voiceassistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPrepTest {
    @Test
    fun smallPhotosAreLeftAlone() {
        assertEquals(1200 to 900, PhotoPrep.scaledSize(1200, 900))
        assertEquals(1, PhotoPrep.sampleSize(1200, 900))
    }

    @Test
    fun theLongerSideIsShrunkToTheLimitKeepingTheShape() {
        assertEquals(1600 to 1200, PhotoPrep.scaledSize(4000, 3000))
        assertEquals(1200 to 1600, PhotoPrep.scaledSize(3000, 4000)) // a portrait photo
        val (w, h) = PhotoPrep.scaledSize(8160, 6120) // a 50 MP phone photo
        assertEquals(1600, w)
        assertEquals(1200, h)
    }

    @Test
    fun theDecodeShrinkNeverGoesBelowTheTarget() {
        for ((w, h) in listOf(4000 to 3000, 8160 to 6120, 3000 to 4000, 1601 to 1000, 6400 to 4800)) {
            val sample = PhotoPrep.sampleSize(w, h)
            assertTrue("power of two", sample and (sample - 1) == 0)
            assertTrue("still big enough to scale down from ($w x $h, sample $sample)", maxOf(w, h) / sample >= PhotoPrep.MAX_SIDE)
        }
        assertEquals(4, PhotoPrep.sampleSize(8160, 6120)) // 2040 px, then scaled the last bit to 1600
    }

    @Test
    fun anAlreadySmallDimensionNeverCollapsesToZero() {
        val (w, h) = PhotoPrep.scaledSize(100000, 3)
        assertEquals(1600, w)
        assertTrue(h >= 1)
    }
}
