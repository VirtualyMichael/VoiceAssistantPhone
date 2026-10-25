package com.voiceassistant.phone

/** What the engine needs from the sound analysis (the real one is sherpa-onnx; the tests use fakes). Samples are 16 kHz mono floats. */
interface Detectors {
    /** True when the wake phrase was just heard. */
    fun wakeWord(samples: FloatArray): Boolean

    /** True while somebody is speaking. */
    fun speaking(samples: FloatArray): Boolean
    fun resetWake()
    fun resetSpeech()
}

/** What the engine does to the outside world. */
interface HandsFreeSink {
    fun sendAudio(frame: ByteArray)
    fun wake()
    fun endOfSpeech()
    fun interrupt()
    fun flushPlayback()
    fun changed()
    fun noSpeech()
}

enum class HfState { Off, Armed, Capturing, Waiting }

/**
 * The hands-free loop. The microphone is always open; nothing leaves the phone until the wake phrase is heard.
 *
 *   Armed     listening for the wake phrase only
 *   Capturing wake phrase heard: audio streams to the PC until you stop talking (or, when the Talk button started it, until you let go)
 *   Waiting   the PC is thinking or speaking; the wake phrase still works, to cut the reply short
 *
 * Time comes from [clock] (milliseconds) so tests can run it without waiting.
 */
class HandsFreeEngine(
    private val detectors: Detectors,
    private val sink: HandsFreeSink,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var state = HfState.Off
        private set

    private val preRoll = ArrayDeque<ByteArray>()
    private var wakeAt = 0L
    private var lastSpeechAt = 0L
    private var waitingSince = 0L
    private var heard = false
    private var held = false
    private var releaseAt = 0L

    fun start() {
        if (state != HfState.Off) return
        detectors.resetWake()
        preRoll.clear()
        set(HfState.Armed)
    }

    fun stop() {
        if (state == HfState.Off) return
        if (state == HfState.Capturing) sink.interrupt()
        preRoll.clear()
        set(HfState.Off)
    }

    /** One 30 ms frame of 16-bit little-endian PCM from the microphone. */
    fun process(pcm: ByteArray) {
        if (state == HfState.Off) return
        val samples = toFloats(pcm)
        val now = clock()
        when (state) {
            HfState.Armed, HfState.Waiting -> {
                preRoll.addLast(pcm)
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                if (state == HfState.Waiting && now - waitingSince > WAIT_MAX_MS) {
                    detectors.resetWake()
                    set(HfState.Armed)
                } else if (detectors.wakeWord(samples)) {
                    beginTurn(held = false)
                }
            }
            HfState.Capturing -> {
                sink.sendAudio(pcm)
                if (held) {
                    if (releaseAt != 0L && now >= releaseAt) finish()
                    return
                }
                val speech = detectors.speaking(samples)
                // the tail of the wake phrase itself sounds like speech, so only speech after a short grace period counts
                if (speech && now - wakeAt >= GRACE_MS) {
                    heard = true
                    lastSpeechAt = now
                }
                when {
                    heard && now - lastSpeechAt >= END_SILENCE_MS -> finish()
                    !heard && now - wakeAt >= NO_SPEECH_MS -> abort()
                    now - wakeAt >= MAX_TURN_MS -> finish()
                }
            }
            HfState.Off -> Unit
        }
    }

    /** The wake phrase went off by mistake (or the user changed their mind): drop what was captured, tell the PC to forget it, and go back to waiting. */
    fun cancel() {
        if (state != HfState.Capturing) return
        sink.interrupt()
        sink.flushPlayback()
        detectors.resetWake()
        preRoll.clear()
        set(HfState.Armed)
    }

    /** The Talk button was pressed while hands-free is on: start a turn without the wake phrase. */
    fun manualPress() {
        if (state == HfState.Off || state == HfState.Capturing) return
        beginTurn(held = true)
    }

    /** The Talk button was let go: keep recording a moment so the last word is not clipped, then finish. */
    fun manualRelease() {
        if (state == HfState.Capturing && held) releaseAt = clock() + RELEASE_TAIL_MS
    }

    /** The PC's state changed (idle, listening, thinking, speaking). */
    fun onPcState(pc: String) {
        if (pc == "idle") {
            when (state) {
                HfState.Waiting -> {
                    detectors.resetWake()
                    set(HfState.Armed)
                }
                HfState.Capturing -> if (clock() - wakeAt > PC_GIVE_UP_AFTER_MS) abort(quiet = true)
                else -> Unit
            }
        }
    }

    /** The link to the PC dropped. */
    fun onDisconnected() {
        if (state == HfState.Capturing || state == HfState.Waiting) {
            detectors.resetWake()
            set(HfState.Armed)
        }
    }

    private fun beginTurn(held: Boolean) {
        val barge = state == HfState.Waiting
        if (barge) {
            sink.flushPlayback()
            sink.interrupt()
        }
        sink.wake()
        wakeAt = clock()
        heard = false
        lastSpeechAt = 0L
        releaseAt = 0L
        this.held = held
        detectors.resetSpeech()
        if (!held) preRoll.takeLast(PRE_ROLL_SENT_FRAMES).forEach { sink.sendAudio(it) }
        preRoll.clear()
        set(HfState.Capturing)
    }

    private fun finish() {
        sink.endOfSpeech()
        waitingSince = clock()
        detectors.resetWake()
        set(HfState.Waiting)
    }

    private fun abort(quiet: Boolean = false) {
        if (!quiet) {
            sink.interrupt() // puts the PC back to idle
            sink.noSpeech()
        }
        detectors.resetWake()
        set(HfState.Armed)
    }

    private fun set(next: HfState) {
        state = next
        sink.changed()
    }

    private fun toFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in out.indices) {
            out[i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() / 32768f
        }
        return out
    }

    companion object {
        const val PRE_ROLL_FRAMES = 12 // 30 ms each: kept so the first word after the wake phrase is not lost
        const val PRE_ROLL_SENT_FRAMES = 8
        const val GRACE_MS = 300L
        const val END_SILENCE_MS = 900L
        const val NO_SPEECH_MS = 4500L // the PC gives up at 5 s, so give up first
        const val MAX_TURN_MS = 30_000L
        const val WAIT_MAX_MS = 90_000L
        const val RELEASE_TAIL_MS = 350L
        const val PC_GIVE_UP_AFTER_MS = 1500L // the PC briefly reports idle while it hands over from a cut-off reply
    }
}
