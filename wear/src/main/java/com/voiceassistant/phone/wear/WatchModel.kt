package com.voiceassistant.phone.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    var heard by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
        private set
    var ask by mutableStateOf<WatchProtocol.Ask?>(null)
        private set
    var recording by mutableStateOf(false)

    fun onState(s: WatchProtocol.State) = main.post {
        phase = if (s.connected) s.phase else "offline"
        phoneFound = true
        if (s.phase == "listening") { heard = ""; reply = "" }
    }

    fun onHeard(text: String) = main.post { heard = text; reply = "" }
    fun onReply(r: WatchProtocol.Reply) = main.post { reply = r.text }
    fun onAsk(a: WatchProtocol.Ask) = main.post { ask = a }
    fun clearAsk() = main.post { ask = null }
    fun newChat() = main.post { heard = ""; reply = ""; ask = null }
    fun phoneMissing() = main.post { phoneFound = false }

    /** The phone's own label for each state, in words for a small screen. */
    fun phaseLabel(): String = when {
        phoneFound == false -> "Phone not found"
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
                if (node == null) WatchModel.phoneMissing()
                done(node?.id)
            }
            .addOnFailureListener { WatchModel.phoneMissing(); done(null) }
    }

    fun send(path: String, data: ByteArray = ByteArray(0)) {
        phoneNode { id -> if (id != null) Wearable.getMessageClient(context).sendMessage(id, path, data) }
    }

    fun hello() = send(WatchProtocol.HELLO)
    fun talkStart() = send(WatchProtocol.TALK_START)
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
