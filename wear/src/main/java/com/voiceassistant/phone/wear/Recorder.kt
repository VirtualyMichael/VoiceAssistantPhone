package com.voiceassistant.phone.wear

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voiceassistant.phone.watch.WatchProtocol
import java.io.OutputStream

/**
 * The watch's microphone, streamed to the phone: 16 kHz 16-bit mono, the format the phone forwards to the PC. Records until [stop], or until
 * [maxSeconds] so a forgotten press cannot keep the microphone and the battery busy. [onDone] is called once, after the stream is closed.
 */
class Recorder(private val maxSeconds: Int = 30) {
    @Volatile private var running = false
    private var thread: Thread? = null

    val active: Boolean get() = running

    @SuppressLint("MissingPermission") // the screen asks for the microphone permission before this is called
    fun start(out: OutputStream, onDone: () -> Unit): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(WatchProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WatchProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, WatchProtocol.FRAME_BYTES * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread {
            val buffer = ByteArray(WatchProtocol.FRAME_BYTES)
            val limit = System.currentTimeMillis() + maxSeconds * 1000L
            try {
                record.startRecording()
                while (running && System.currentTimeMillis() < limit) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
            } catch (_: java.io.IOException) {
                // the phone went away mid-question: just stop
            } finally {
                running = false
                runCatching { record.stop() }
                record.release()
                runCatching { out.flush(); out.close() }
                onDone()
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        running = false
    }
}
