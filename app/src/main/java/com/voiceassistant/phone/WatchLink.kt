package com.voiceassistant.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Wearable
import com.voiceassistant.phone.watch.WatchProtocol

/**
 * The phone's side of the watch link: sends the watch the assistant's state, what it heard, the reply as it grows, and questions that need
 * yes or no. It only does anything when the user turned "Let my watch talk to the assistant" on (see [Controller.watchEnabled]); the
 * receiving side is [WatchService]. The watch never reaches the PC itself; everything it says goes through the phone.
 */
object WatchLink {
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    @Volatile var enabled = false
    private var pendingReply: String? = null
    private var replyScheduled = false
    private var lastState: WatchProtocol.State? = null

    fun init(appContext: Context) { context = appContext.applicationContext }

    private fun send(path: String, data: ByteArray) {
        if (!enabled || !::context.isInitialized) return
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                val messages = Wearable.getMessageClient(context)
                nodes.forEach { messages.sendMessage(it.id, path, data) }
            }
        }
    }

    /** The assistant's state, or the link to the PC, changed. */
    fun state(phase: String, connected: Boolean, offlineHelper: Boolean) {
        val s = WatchProtocol.State(phase, connected, offlineHelper)
        if (s == lastState) return
        lastState = s
        send(WatchProtocol.STATE, WatchProtocol.encode(s))
        if (phase == "idle" || phase == "listening") flushReply(done = phase == "idle")
    }

    fun heard(text: String) = send(WatchProtocol.HEARD, WatchProtocol.encodeText(text))

    /** The reply so far; sent at most about four times a second, because every message costs the watch's battery. */
    fun reply(textSoFar: String) {
        if (!enabled) return
        pendingReply = textSoFar
        if (!replyScheduled) {
            replyScheduled = true
            main.postDelayed({ replyScheduled = false; flushReply(done = false) }, 250)
        }
    }

    private fun flushReply(done: Boolean) {
        val text = pendingReply ?: return
        if (done) pendingReply = null
        send(WatchProtocol.REPLY, WatchProtocol.encode(WatchProtocol.Reply(text, done)))
    }

    fun ask(id: String, prompt: String, kind: String) = send(WatchProtocol.ASK, WatchProtocol.encode(WatchProtocol.Ask(id, prompt, kind)))

    /** The watch app opened and wants to know where things stand. */
    fun hello(phase: String, connected: Boolean, offlineHelper: Boolean) {
        lastState = null
        state(phase, connected, offlineHelper)
    }
}
