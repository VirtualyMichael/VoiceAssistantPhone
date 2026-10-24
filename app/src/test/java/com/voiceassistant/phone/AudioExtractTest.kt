package com.voiceassistant.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioExtractTest {
    @Test
    fun stereoIsAveragedToMono() {
        assertArrayEquals(shortArrayOf(5, 25), AudioExtract.toMono(shortArrayOf(0, 10, 20, 30), 2))
        assertArrayEquals(shortArrayOf(1, 2, 3), AudioExtract.toMono(shortArrayOf(1, 2, 3), 1))
    }

    @Test
    fun aDownsampledToneKeepsItsLengthAndLevel() {
        val rate = 48_000
        val tone = ShortArray(rate) { (12_000 * sin(2 * PI * 440 * it / rate)).toInt().toShort() } // one second
        val out = AudioExtract.resample(tone, rate, AudioExtract.TARGET_RATE)
        assertEquals(AudioExtract.TARGET_RATE, out.size)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("level kept ($peak)", peak in 9_000..12_100)
    }

    @Test
    fun sameRateIsLeftAloneAndUpsamplingInterpolates() {
        val same = shortArrayOf(1, 2, 3)
        assertArrayEquals(same, AudioExtract.resample(same, 16_000, 16_000))
        val up = AudioExtract.resample(shortArrayOf(0, 100), 8_000, 16_000)
        assertEquals(4, up.size)
        assertEquals(0, up[0].toInt())
        assertEquals(50, up[1].toInt())
    }

    @Test
    fun samplesBecomeLittleEndianBytes() {
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte()), AudioExtract.toBytes(shortArrayOf(1, -1, Short.MIN_VALUE)))
    }
}
