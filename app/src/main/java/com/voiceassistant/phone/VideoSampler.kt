package com.voiceassistant.phone

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One picked frame: where it is in the clip, and its JPEG. */
class SampledFrame(val timeMs: Long, val jpeg: ByteArray)

/**
 * Turns a recorded clip into a few small pictures on the phone, so only those cross the link and never the video itself.
 * The plan is the same one the PC uses for videos it is given: evenly spaced moments, no more than the quality level allows.
 */
object VideoSampler {
    const val MAX_FRAME_BYTES = 450_000

    /** About what one frame at this size weighs as a JPEG (16:9). The same estimate the PC uses (video_look.py estimate_frame_bytes). */
    fun estimateFrameBytes(edge: Int): Long = max((0.09 * edge * edge * 9 / 16).toLong(), 1000L)

    /** How many frames a clip of [spanSeconds] gets: limited by the frame rate, the frame cap and the data budget, never fewer than two. */
    fun frameCount(spanSeconds: Double, maxFrames: Int, maxFps: Float, maxBytes: Long, edge: Int): Int {
        if (spanSeconds < 1.0) return 2
        val byRate = ceil(spanSeconds * maxFps).toInt()
        val bySize = (maxBytes / estimateFrameBytes(edge)).toInt()
        return max(2, min(maxFrames, min(byRate, bySize)))
    }

    /** The moments to pick: the middle of [frameCount] equal slices of the clip. */
    fun planTimesMs(durationMs: Long, maxFrames: Int, maxFps: Float, maxBytes: Long, edge: Int): List<Long> {
        val span = max(durationMs / 1000.0, 0.1)
        val n = frameCount(span, maxFrames, maxFps, maxBytes, edge)
        return (0 until n).map { i -> ((i + 0.5) * span / n * 1000).roundToInt().toLong() }
    }

    /** If the frames add up to more than the data budget, keep evenly spaced ones (always the first and last) until they fit. */
    fun fitBudget(frames: List<SampledFrame>, maxBytes: Long): List<SampledFrame> {
        val total = frames.sumOf { it.jpeg.size.toLong() }
        if (total <= maxBytes || frames.size <= 2) return frames
        val keep = max(2, (frames.size * maxBytes / total).toInt())
        val indices = (0 until keep).map { i -> Math.round(i * (frames.size - 1).toDouble() / (keep - 1)).toInt() }.distinct()
        return indices.map { frames[it] }
    }

    /** Size after scaling so the longer side is at most [maxEdge] (a smaller picture is left alone). */
    fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> = PhotoPrep.scaledSize(width, height, maxEdge)

    /** Reads [file] and returns the picked frames and the clip's length. [onProgress] gets 0..1. Throws if the clip cannot be read. */
    fun sample(file: File, maxFrames: Int, maxFps: Float, maxEdge: Int, maxBytes: Long, onProgress: (Float) -> Unit = {}): Pair<List<SampledFrame>, Long> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0) throw IllegalStateException("the clip has no length")
            val times = planTimesMs(durationMs, maxFrames, maxFps, maxBytes, maxEdge)
            val frames = ArrayList<SampledFrame>(times.size)
            for ((i, t) in times.withIndex()) {
                val bitmap = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                try {
                    frames += SampledFrame(t, jpeg(bitmap, maxEdge))
                } finally {
                    bitmap.recycle()
                }
                onProgress((i + 1f) / times.size)
            }
            if (frames.isEmpty()) throw IllegalStateException("no pictures could be read from the clip")
            return fitBudget(frames, maxBytes) to durationMs
        } finally {
            retriever.release()
        }
    }

    private fun jpeg(source: Bitmap, maxEdge: Int): ByteArray {
        val (w, h) = scaledSize(source.width, source.height, maxEdge)
        val scaled = if (w != source.width) Bitmap.createScaledBitmap(source, w, h, true) else source
        try {
            var out = ByteArray(0)
            for (quality in intArrayOf(80, 70, 60, 50, 40)) {
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                out = stream.toByteArray()
                if (out.size <= MAX_FRAME_BYTES) break
            }
            return out
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
