package com.voiceassistant.phone.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.voiceassistant.phone.watch.WatchProtocol

/** What the screen shows, kept up to date by [WatchListener] from the phone's messages. Everything here lives only while the app does. */
object WatchModel {
    private val main = Handler(Looper.getMainLooper())

    /** idle, listening, thinking, speaking, or offline (the phone cannot reach the PC); "" until the phone has answered. */
    var phase by mutableStateOf("")
        private set
    var phoneFound by mutableStateOf<Boolean?>(null) // null while still looking
        private set
    /** Why the watch cannot be used right now, when the phone was found but something else is wrong: one of the PROBLEM_ values, or "". */
    var problem by mutableStateOf("")
        private set
    var heard by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var ask by mutableStateOf<WatchProtocol.Ask?>(null)
        private set
    var recording by mutableStateOf(false)

    const val PROBLEM_NO_ANSWER = "no_answer" // the phone is there, but its app does not answer (not running, or cannot start)
    const val PROBLEM_OFF = "off" // the phone app answered: the watch is switched off in its settings
    const val PROBLEM_LOST = "lost" // the phone stopped answering in the middle of a question

    /** Sends the phone a hello (set by the screen); used to check the phone is still there when it has gone quiet mid-question. */
    var ping: (() -> Unit)? = null

    private const val ANSWER_MS = 6_000L // how long a hello may take to be answered
    private const val QUIET_MS = 15_000L // how long the phone may say nothing mid-question before the watch checks on it
    private const val TICK_MS = 5_000L
    @Volatile private var lastHeard = 0L
    private var busySince = 0L
    private var ticking = false

    private fun now() = SystemClock.elapsedRealtime()

    /** Anything at all arrived from the phone (called on the listener's thread). */
    fun heardFromPhone() { lastHeard = now() }

    fun onState(s: WatchProtocol.State) = main.post {
        phoneFound = true
        if (s.phase == WatchProtocol.PHASE_OFF) {
            problem = PROBLEM_OFF
            phase = ""
            return@post
        }
        problem = ""
        phase = if (s.connected) s.phase else "offline"
        if (s.phase == "listening") { heard = ""; reply = "" }
        keepWatch()
    }

    fun onHeard(text: String) = main.post { heard = text; reply = "" }
    fun onReply(r: WatchProtocol.Reply) = main.post { reply = r.text }
    fun onAsk(a: WatchProtocol.Ask) = main.post { ask = a }
    fun clearAsk() = main.post { ask = null }
    fun newChat() = main.post { heard = ""; reply = ""; ask = null }

    /** A quick reply was tapped: show it as what was heard, so the screen answers straight away. */
    fun sentQuick(text: String) = main.post { heard = text; reply = ""; phase = "thinking"; busyNow() }
    fun phoneMissing() = main.post { phoneFound = false }
    fun phoneSeen() = main.post { if (phoneFound == false) phoneFound = null }

    /** The microphone opened, or a question went out: from now on the watch keeps an eye on the phone answering. */
    fun busyNow() = main.post { busySince = now(); keepWatch() }

    /** True when Talk should only check the phone again, because the last check already said it cannot work. */
    fun needsCheck(): Boolean = phoneFound == false || problem == PROBLEM_OFF || problem == PROBLEM_NO_ANSWER

    /** Talk was tapped while something was wrong: forget the old answer and show "Connecting…" while the phone is asked again. */
    fun checkAgain() = main.post { phoneFound = null; problem = ""; phase = "" }

    /** A hello just went out: if nothing at all comes back in a few seconds, the phone app is not running or cannot start. */
    fun expectAnswer() = main.post {
        val asked = now()
        main.postDelayed({ if (lastHeard < asked && phoneFound != false) noAnswer() }, ANSWER_MS)
    }

    private fun busy() = recording || phase == "listening" || phase == "thinking" || phase == "speaking"

    private fun noAnswer() {
        problem = if (busy()) PROBLEM_LOST else PROBLEM_NO_ANSWER
        phase = "" // never left on "listening" or "thinking" with nobody there
        recording = false
        ReplyPlayer.stop()
    }

    /** While a question is under way, check every few seconds that the phone has not gone quiet; a quiet phone gets a hello. */
    private fun keepWatch() {
        if (ticking || !busy()) return
        ticking = true
        main.postDelayed(::tick, TICK_MS)
    }

    private fun tick() {
        ticking = false
        if (!busy()) return
        if (now() - maxOf(lastHeard, busySince) > QUIET_MS) {
            busySince = now() // one check at a time: the next one only after another quiet spell
            ping?.invoke()
        }
        keepWatch()
    }

    /** The phone's own label for each state, in words for a small screen. */
    fun phaseLabel(): String = when {
        phoneFound == false -> "Phone not found"
        problem == PROBLEM_OFF -> "Switched off on your phone. In the phone app, Settings, turn on “Let my watch talk to the assistant”."
        problem == PROBLEM_NO_ANSWER -> "Your phone isn't answering. Open Voice Assistant on your phone."
        problem == PROBLEM_LOST -> "Lost touch with your phone. Tap Talk to try again."
        recording -> "Listening…"
        phase == "listening" -> "Listening…"
        phase == "thinking" -> "Thinking…"
        phase == "speaking" -> "Speaking…"
        phase == "offline" -> "Phone can't reach your PC"
        phase == "idle" -> "Tap to talk"
        else -> "Connecting…"
    }
}
/** Finds the phone app and sends it things. Messages are small and go out on a worker thread because Google's client is asynchronous. */
class PhoneLink(private val context: Context) {
    private fun phoneNode(done: (String?) -> Unit) {
        Wearable.getCapabilityClient(context).getCapability(WatchProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
                android.util.Log.i("VAWatch", "phone nodes found: ${info.nodes.map { it.displayName + (if (it.isNearby) " (nearby)" else "") }}")
                if (node == null) WatchModel.phoneMissing() else WatchModel.phoneSeen()
                done(node?.id)
            }
            .addOnFailureListener { android.util.Log.w("VAWatch", "capability lookup failed", it); WatchModel.phoneMissing(); done(null) }
    }

    fun send(path: String, data: ByteArray = ByteArray(0)) {
        phoneNode { id ->
            if (id != null) Wearable.getMessageClient(context).sendMessage(id, path, data)
                .addOnSuccessListener { android.util.Log.i("VAWatch", "sent $path") }
                .addOnFailureListener { android.util.Log.w("VAWatch", "send $path failed", it) }
        }
    }

    private fun helloData() = WatchProtocol.encode(WatchProtocol.Hello(ReplyPlayer.hasSpeaker(context)))

    /** Asks the phone where things stand; no answer at all within a few seconds means its app is not running (see [WatchModel.expectAnswer]). */
    fun hello() {
        WatchModel.expectAnswer()
        send(WatchProtocol.HELLO, helloData())
    }
    fun talkStart() = send(WatchProtocol.TALK_START, helloData())
    fun talkEnd() = send(WatchProtocol.TALK_END)
    fun stop() = send(WatchProtocol.STOP)
    fun newChat() = send(WatchProtocol.NEW_CHAT)
    fun quick(text: String) = send(WatchProtocol.QUICK, WatchProtocol.encodeText(text))
    fun answer(ok: Boolean) = send(WatchProtocol.ANSWER, WatchProtocol.encodeAnswer(ok))

    /** Opens the stream the spoken question goes through; [onReady] gets the output stream (or null when the phone cannot be reached). */
    fun openAudio(onReady: (java.io.OutputStream?) -> Unit) {
        phoneNode { id ->
            if (id == null) { onReady(null); return@phoneNode }
            val client = Wearable.getChannelClient(context)
            client.openChannel(id, WatchProtocol.AUDIO_CHANNEL)
                .addOnSuccessListener { channel -> client.getOutputStream(channel).addOnSuccessListener { onReady(it) }.addOnFailureListener { onReady(null) } }
                .addOnFailureListener { onReady(null) }
        }
    }
}
