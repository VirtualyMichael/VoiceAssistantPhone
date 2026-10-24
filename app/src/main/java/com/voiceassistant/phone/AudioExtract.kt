package com.voiceassistant.phone

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * The sound of a recorded clip as 16 kHz mono 16-bit samples, which is what the PC's speech recognizer wants (about 2 MB a minute).
 * Only the sound leaves the phone, in small pieces, never the clip. The conversions are plain functions so they can be tested without a phone.
 */
object AudioExtract {
    const val TARGET_RATE = 16_000
    const val MAX_SECONDS = 240

    /** Several channels averaged into one. */
    fun toMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += samples[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Sample rate conversion. Going down averages the samples each output sample covers (a cheap low-pass, fine for speech); going up interpolates. */
    fun resample(mono: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || mono.isEmpty()) return mono
        val ratio = fromRate.toDouble() / toRate
        val outSize = (mono.size / ratio).toInt()
        return ShortArray(outSize) { i ->
            val start = i * ratio
            val a = start.toInt()
            if (ratio > 1.0) {
                val end = min(mono.size, maxOf(a + 1, (start + ratio).toInt()))
                var sum = 0L
                for (k in a until end) sum += mono[k]
                (sum / (end - a)).toShort()
            } else {
                val b = min(mono.size - 1, a + 1)
                val t = start - a
                (mono[a] * (1 - t) + mono[b] * t).toInt().toShort()
            }
        }
    }

    /** Little-endian 16-bit bytes, as the PC reads them. */
    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            out[2 * i] = (s.toInt() and 0xFF).toByte()
            out[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The sound track of [file]: empty when the clip has none, null when it could not be read. */
    fun extract(file: File, maxSeconds: Int = MAX_SECONDS): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return ByteArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var mono = ShortArray(rate * 30)
            var length = 0
            val info = MediaCodec.BufferInfo()
            val limitUs = maxSeconds * 1_000_000L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0 || extractor.sampleTime > limitUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buffer = codec.getOutputBuffer(o)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2)
                    buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    val piece = toMono(shorts, channels)
                    if (length + piece.size > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, length + piece.size))
                    piece.copyInto(mono, length)
                    length += piece.size
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            return toBytes(resample(mono.copyOf(length), rate, TARGET_RATE))
        } catch (e: Exception) {
            android.util.Log.w("VoiceAssistant", "could not read the clip's sound", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
