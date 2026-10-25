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

    const val SAMPLE_RATE = 16_000
    const val FRAME_BYTES = SAMPLE_RATE * 2 * 30 / 1000 // 30 ms, the size the PC link takes
    const val MAX_TEXT_CHARS = 4000

    data class State(val phase: String, val connected: Boolean, val offlineHelper: Boolean)

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
}
