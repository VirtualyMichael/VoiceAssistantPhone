package com.voiceassistant.phone

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Captures the microphone as 16 kHz mono 16-bit PCM in 30 ms frames, the format the PC's recognizer expects. */
class Mic(private val onFrame: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before starting
    fun start(): Boolean {
        if (running) return true
        val frameBytes = SAMPLE_RATE * 2 * FRAME_MS / 1000
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        // VOICE_COMMUNICATION turns on the phone's own echo cancellation and noise suppression.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frameBytes * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread {
            record.startRecording()
            val buffer = ByteArray(frameBytes)
            while (running) {
                var filled = 0
                while (running && filled < frameBytes) {
                    val n = record.read(buffer, filled, frameBytes - filled)
                    if (n <= 0) { running = false; break }
                    filled += n
                }
                if (filled == frameBytes) onFrame(buffer.copyOf())
            }
            record.stop()
            record.release()
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_MS = 30
    }
}
