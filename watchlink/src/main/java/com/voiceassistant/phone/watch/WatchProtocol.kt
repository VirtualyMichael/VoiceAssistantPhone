package com.voiceassistant.phone.watch

import org.json.JSONObject

/**
 * What the watch app and the phone app say to each other over Wear OS's Data Layer (see docs/WATCH.md). This one file is compiled into both
 * apps, so they can never disagree about a path or a field. The watch never talks to the PC: everything goes through the phone.
 *
 * Small things are messages (a path plus a little JSON); the spoken question is a channel (a stream of raw 16 kHz 16-bit mono audio).
 */
object WatchProtocol {
    /** The phone app tells watches it is there with this capability; the watch app tells phones with the other one. */
    const val CAPABILITY_PHONE = "voice_assistant_phone"
    const val CAPABILITY_WATCH = "voice_assistant_watch"

    // watch -> phone
    const val HELLO = "/va/hello" // the watch app opened: send me the current state
    const val TALK_START = "/va/talk/start" // the button was pressed: the assistant starts listening
    const val AUDIO_CHANNEL = "/va/audio" // a channel carrying raw PCM until the watch closes it
    const val TALK_END = "/va/talk/end" // the button was released: that was the whole question
    const val QUICK = "/va/quick" // a typed or dictated message (the text itself)
    const val ANSWER = "/va/answer" // {"ok": true|false} to the question on the watch
    const val NEW_CHAT = "/va/new_chat"
    const val STOP = "/va/stop" // stop speaking

    // phone -> watch
    const val STATE = "/va/state"
    const val HEARD = "/va/heard" // what the assistant understood you said
    const val REPLY = "/va/reply" // the reply so far (or finished)
    const val ASK = "/va/ask" // a question that needs yes or no
    const val REPLY_AUDIO_CHANNEL = "/va/reply_audio" // a channel carrying the spoken reply, in [ReplyAudio] pieces
    const val REPLY_AUDIO_CANCEL = "/va/reply_audio/cancel" // {"id": n}: stop playing that reply now (you or the phone interrupted it)

    const val SAMPLE_RATE = 16_000
    const val FRAME_BYTES = SAMPLE_RATE * 2 * 30 / 1000 // 30 ms, the size the PC link takes
    const val MAX_TEXT_CHARS = 4000

    /**
     * The phase the phone sends when "Let my watch talk to the assistant" is off. It is the only thing the phone ever says while switched off,
     * and only as the answer to [HELLO]: no state, no PC, nothing else, so the watch can tell the user where to switch it on.
     */
    const val PHASE_OFF = "off"

    data class State(val phase: String, val connected: Boolean, val offlineHelper: Boolean)

    /** Sent with [HELLO] and [TALK_START]: whether the watch can play sound at all (some have no speaker). */
    data class Hello(val speaker: Boolean)

    fun encode(h: Hello): ByteArray = JSONObject().put("speaker", h.speaker).toString().toByteArray()

    /** An old watch app sent an empty hello: treat it as having no speaker, so no audio is sent that nothing would play. */
    fun decodeHello(data: ByteArray): Hello = Hello(parse(data)?.optBoolean("speaker", false) ?: false)

    fun encodeCancel(id: Int): ByteArray = JSONObject().put("id", id).toString().toByteArray()
    fun decodeCancel(data: ByteArray): Int? = parse(data)?.takeIf { it.has("id") }?.optInt("id", -1)?.takeIf { it > 0 }

    data class Reply(val text: String, val done: Boolean)

    /** [kind] is "approval" (the assistant wants to do something) or "action" (an alarm, timer or event on the phone). */
    data class Ask(val id: String, val prompt: String, val kind: String)

    fun encode(s: State): ByteArray = JSONObject().put("phase", s.phase).put("connected", s.connected).put("offline", s.offlineHelper).toString().toByteArray()
    fun encode(r: Reply): ByteArray = JSONObject().put("text", r.text.take(MAX_TEXT_CHARS)).put("done", r.done).toString().toByteArray()
    fun encode(a: Ask): ByteArray = JSONObject().put("id", a.id).put("prompt", a.prompt.take(MAX_TEXT_CHARS)).put("kind", a.kind).toString().toByteArray()
    fun encodeAnswer(ok: Boolean): ByteArray = JSONObject().put("ok", ok).toString().toByteArray()
    fun encodeText(text: String): ByteArray = text.take(MAX_TEXT_CHARS).toByteArray()

    private fun parse(data: ByteArray): JSONObject? = runCatching { JSONObject(String(data)) }.getOrNull()

    fun decodeState(data: ByteArray): State? = parse(data)?.let { State(it.optString("phase", "idle"), it.optBoolean("connected"), it.optBoolean("offline")) }
    fun decodeReply(data: ByteArray): Reply? = parse(data)?.let { Reply(it.optString("text").take(MAX_TEXT_CHARS), it.optBoolean("done")) }
    fun decodeAsk(data: ByteArray): Ask? = parse(data)?.takeIf { it.optString("prompt").isNotBlank() }?.let { Ask(it.optString("id"), it.optString("prompt"), it.optString("kind", "approval")) }
    fun decodeAnswer(data: ByteArray): Boolean? = parse(data)?.takeIf { it.has("ok") }?.optBoolean("ok")
    fun decodeText(data: ByteArray): String = String(data).take(MAX_TEXT_CHARS).trim()

    /** Cuts a stream of audio bytes into whole frames, keeping the leftover for the next piece (a channel delivers whatever size it likes). */
    class Framer(private val frameBytes: Int = FRAME_BYTES) {
        private var rest = ByteArray(0)

        fun push(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
            val all = rest + bytes.copyOf(length)
            val frames = ArrayList<ByteArray>()
            var at = 0
            while (all.size - at >= frameBytes) {
                frames += all.copyOfRange(at, at + frameBytes)
                at += frameBytes
            }
            rest = all.copyOfRange(at, all.size)
            return frames
        }
    }

    /**
     * The spoken reply, phone to watch, on a [REPLY_AUDIO_CHANNEL]. The channel is a plain byte stream, so it is cut into pieces:
     * one kind byte, a 4-byte big-endian length, then that many bytes.
     *  - start: the reply's id and its sample rate (two 4-byte numbers). Comes first, and again if the rate changes mid-reply.
     *  - pcm: 16-bit little-endian mono audio, at most [MAX_PIECE] bytes.
     *  - end: nothing more is coming; play what is left, then close.
     */
    object ReplyAudio {
        const val KIND_START: Byte = 1
        const val KIND_PCM: Byte = 2
        const val KIND_END: Byte = 3
        const val MAX_PIECE = 64 * 1024
        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000
        private const val HEADER = 5

        sealed interface Piece
        data class Start(val id: Int, val sampleRate: Int) : Piece
        class Pcm(val bytes: ByteArray) : Piece
        object End : Piece

        private fun piece(kind: Byte, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
            val out = ByteArray(HEADER + length)
            out[0] = kind
            putInt(out, 1, length)
            System.arraycopy(payload, offset, out, HEADER, length)
            return out
        }

        fun start(id: Int, sampleRate: Int): ByteArray = piece(KIND_START, ByteArray(8).also { putInt(it, 0, id); putInt(it, 4, sampleRate) })

        /** Audio of any length, cut into pieces of at most [MAX_PIECE] bytes. */
        fun pcm(bytes: ByteArray): List<ByteArray> =
            (bytes.indices step MAX_PIECE).map { at -> piece(KIND_PCM, bytes, at, minOf(MAX_PIECE, bytes.size - at)) }

        fun end(): ByteArray = piece(KIND_END, ByteArray(0))

        private fun putInt(to: ByteArray, at: Int, v: Int) {
            to[at] = (v ushr 24).toByte(); to[at + 1] = (v ushr 16).toByte(); to[at + 2] = (v ushr 8).toByte(); to[at + 3] = v.toByte()
        }

        private fun getInt(from: ByteArray, at: Int): Int =
            ((from[at].toInt() and 0xFF) shl 24) or ((from[at + 1].toInt() and 0xFF) shl 16) or ((from[at + 2].toInt() and 0xFF) shl 8) or (from[at + 3].toInt() and 0xFF)

        /**
         * Reads pieces back out of whatever chunks the channel delivers; a piece split across chunks waits for the rest. Anything that does not
         * look like this format (an unknown kind, a length that is too big, audio before a start, a sample rate no speaker plays) marks the stream
         * [broken], and from then on nothing more is returned: the watch stops rather than play noise.
         */
        class Reader {
            private var rest = ByteArray(0)
            private var started = false
            var broken = false
                private set
            var ended = false
                private set

            fun push(bytes: ByteArray, length: Int = bytes.size): List<Piece> {
                if (broken || ended) return emptyList()
                val all = rest + bytes.copyOf(length)
                val pieces = ArrayList<Piece>()
                var at = 0
                while (all.size - at >= HEADER) {
                    val kind = all[at]
                    val size = getInt(all, at + 1)
                    val valid = when (kind) {
                        KIND_START -> size == 8
                        KIND_PCM -> size in 1..MAX_PIECE
                        KIND_END -> size == 0
                        else -> false
                    }
                    if (!valid) { broken = true; break }
                    if (all.size - at - HEADER < size) break // the rest of this piece is still on its way
                    val body = all.copyOfRange(at + HEADER, at + HEADER + size)
                    at += HEADER + size
                    when (kind) {
                        KIND_START -> {
                            val rate = getInt(body, 4)
                            if (rate !in MIN_RATE..MAX_RATE) { broken = true; break }
                            started = true
                            pieces += Start(getInt(body, 0), rate)
                        }
                        KIND_PCM -> {
                            if (!started) { broken = true; break }
                            pieces += Pcm(body)
                        }
                        else -> { ended = true; pieces += End; break }
                    }
                }
                rest = if (broken || ended) ByteArray(0) else all.copyOfRange(at, all.size)
                return pieces
            }
        }
    }
}
