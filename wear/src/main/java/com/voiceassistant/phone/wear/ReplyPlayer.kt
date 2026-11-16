package com.voiceassistant.phone.wear

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.voiceassistant.phone.watch.WatchProtocol.ReplyAudio
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the spoken reply the phone streams to the watch (when "Where replies play when I ask from my watch" is Watch). It starts as soon as
 * the first audio arrives and stops at once on [stop]: the Stop button, a new Talk press, or the phone saying the reply was cut off.
 * One reply plays at a time; a new one replaces the old.
 */
object ReplyPlayer {
    private val generation = AtomicInteger()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var channel: ChannelClient.Channel? = null
    @Volatile private var replyId = 0
    @Volatile private var appContext: Context? = null

    /** Some watches have no speaker at all: those show the reply as text only, and the phone is told not to send audio. */
    fun hasSpeaker(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)

    fun play(context: Context, opened: ChannelClient.Channel) {
        val app = context.applicationContext
        appContext = app
        stop()
        val gen = generation.get()
        channel = opened
        Thread {
            val client = Wearable.getChannelClient(app)
            var written = 0L // in samples
            var local: AudioTrack? = null
            try {
                val input = Tasks.await(client.getInputStream(opened), 5, TimeUnit.SECONDS)
                val reader = ReplyAudio.Reader()
                val buffer = ByteArray(4096)
                input.use {
                    read@ while (gen == generation.get()) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        for (piece in reader.push(buffer, n)) {
                            if (gen != generation.get()) break@read
                            when (piece) {
                                is ReplyAudio.Start -> {
                                    replyId = piece.id
                                    if (local?.sampleRate != piece.sampleRate) {
                                        local?.let { t -> drain(t, written, gen); t.release() }
                                        written = 0
                                        local = open(piece.sampleRate)
                                        track = local
                                    }
                                }
                                is ReplyAudio.Pcm -> local?.let { t -> if (t.write(piece.bytes, 0, piece.bytes.size) > 0) written += piece.bytes.size / 2 }
                                ReplyAudio.End -> break@read
                            }
                        }
                        if (reader.broken) {
                            android.util.Log.w("VAWatch", "reply audio was not in the expected format; stopped")
                            break
                        }
                    }
                }
                local?.let { drain(it, written, gen) } // let the last words play out
            } catch (e: Exception) {
                android.util.Log.i("VAWatch", "reply audio ended: ${e.message}") // Stop, or the phone went out of reach
            } finally {
                local?.let { t -> runCatching { t.stop() }; t.release() }
                if (track === local) track = null
                if (gen == generation.get()) { channel = null; replyId = 0 }
                runCatching { client.close(opened) }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stop now, dropping whatever has not been played yet. */
    fun stop() {
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } } // frees a write that is waiting for room, so the reading thread notices at once
        channel?.let { c -> appContext?.let { runCatching { Wearable.getChannelClient(it).close(c) } } }
        channel = null
        replyId = 0
    }

    /** The phone cut reply [id] off (or someone pressed Stop on the phone). A cancel for an older reply leaves a newer one playing. */
    fun cancel(id: Int) {
        if (id == replyId || replyId == 0) stop()
    }

    /** Waits until everything written has been heard, unless [stop] comes first. */
    private fun drain(t: AudioTrack, writtenSamples: Long, gen: Int) {
        val limit = System.currentTimeMillis() + 15_000
        while (gen == generation.get() && System.currentTimeMillis() < limit && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < writtenSamples) {
            Thread.sleep(40)
        }
    }

    private fun open(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate)) // about half a second: the phone sends a little ahead of playback
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }
}

