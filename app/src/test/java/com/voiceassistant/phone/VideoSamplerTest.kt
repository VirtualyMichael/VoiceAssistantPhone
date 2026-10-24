package com.voiceassistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plan must match the PC's (voice_assistant/video_look.py frame_count and plan_times), so the same levels mean the same thing on both. */
class VideoSamplerTest {
    private class Level(val edge: Int, val mb: Double, val fps: Float, val frames: Int) {
        val bytes get() = (mb * 1_000_000).toLong()
    }

    private val normal = Level(640, 5.0, 2f, 100)
    private val mid = Level(960, 10.0, 3f, 200)
    private val high = Level(1280, 50.0, 6f, 400)

    private fun plan(durationMs: Long, level: Level) = VideoSampler.planTimesMs(durationMs, level.frames, level.fps, level.bytes, level.edge)

    private fun frame(t: Long, size: Int) = SampledFrame(t, ByteArray(size))

    @Test
    fun aTwelveSecondClipGetsTwoFramesASecondInTheMiddleOfEachHalfSecond() {
        assertEquals((0 until 24).map { 250L + it * 500 }, plan(12_000, normal))
    }

    @Test
    fun theFrameRateTheCapAndTheDataBudgetAllLimitTheCount() {
        assertEquals(100, plan(600_000, normal).size) // the cap
        assertEquals(36, plan(12_000, mid).size)
        assertEquals(180, plan(30_000, high).size)
        assertEquals(400, plan(600_000, high).size)
        val tight = Level(1280, 1.0, 10f, 500) // about 83 KB a frame, so about 12 frames fit in 1 MB
        assertTrue(plan(60_000, tight).size in 10..14)
        assertTrue(plan(60_000, Level(1920, 5.0, 10f, 500)).size < plan(60_000, Level(320, 5.0, 10f, 500)).size)
    }

    @Test
    fun aBlinkStillGetsTwoLooksAndTimesStayInsideTheClip() {
        assertEquals(2, plan(400, normal).size)
        for (level in listOf(normal, mid, high)) {
            val times = plan(17_300, level)
            assertTrue(times.all { it in 0..17_300 })
            assertEquals(times.sorted(), times)
        }
    }

    @Test
    fun theEstimateGrowsWithThePictureSize() {
        assertEquals(20_736L, VideoSampler.estimateFrameBytes(640))
        assertTrue(VideoSampler.estimateFrameBytes(1280) > VideoSampler.estimateFrameBytes(640))
        assertEquals(1000L, VideoSampler.estimateFrameBytes(10))
    }

    @Test
    fun framesThatAddUpToTooMuchAreThinnedEvenlyKeepingBothEnds() {
        val fat = (0 until 100).map { frame(it * 1000L, 1000) }
        val kept = VideoSampler.fitBudget(fat, 40_000)
        assertTrue(kept.size in 38..40)
        assertEquals(0L, kept.first().timeMs)
        assertEquals(99_000L, kept.last().timeMs)
        assertEquals(fat, VideoSampler.fitBudget(fat, 1_000_000))
        assertEquals(2, VideoSampler.fitBudget(fat, 10).size)
    }

    @Test
    fun closeUpFramesAreSpreadEvenlyAcrossTheClip() {
        assertEquals(listOf(2_000L, 6_000L, 10_000L), VideoSampler.detailTimesMs(12_000, 3))
        assertEquals(emptyList<Long>(), VideoSampler.detailTimesMs(12_000, 0))
        assertEquals(listOf(5_000L), VideoSampler.detailTimesMs(10_000, 1))
    }

    @Test
    fun framesAreShrunkToTheLevelsSizeAndNeverEnlarged() {
        assertEquals(640 to 360, VideoSampler.scaledSize(1280, 720, 640))
        assertEquals(360 to 640, VideoSampler.scaledSize(720, 1280, 640)) // a portrait clip
        assertEquals(960 to 540, VideoSampler.scaledSize(1920, 1080, 960))
        assertEquals(320 to 240, VideoSampler.scaledSize(320, 240, 640))
    }
}
