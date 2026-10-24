package com.voiceassistant.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Plays the PC's spoken reply as it streams in. [flush] drops whatever has not been played yet (the user interrupted). */
class Player {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
    private val generation = AtomicInteger()
    private var track: AudioTrack? = null
    private var rate = 0
    private var openedAsAssistant = false

    /**
     * While the microphone stays open (hands-free) the reply is tagged as assistant audio, the kind a voice assistant uses: it plays on the
     * loudspeaker at the normal volume (call audio would use the quiet earpiece) and the phone's echo cancellation can subtract it.
     */
    @Volatile var assistantAudio = false

    /** A new sentence is about to arrive at this sample rate. */
    fun start(sampleRate: Int) {
        val gen = generation.get()
        worker.execute { if (gen == generation.get()) open(sampleRate) }
    }

    fun write(pcm: ByteArray) {
        val gen = generation.get()
        worker.execute { if (gen == generation.get()) track?.write(pcm, 0, pcm.size) }
    }

    fun flush() {
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    fun release() {
        generation.incrementAndGet()
        worker.execute { track?.release(); track = null }
    }

    private fun open(sampleRate: Int) {
        if (track != null && rate == sampleRate && openedAsAssistant == assistantAudio) return
        track?.release()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (assistantAudio) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate)) // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        rate = sampleRate
        openedAsAssistant = assistantAudio
    }
}
