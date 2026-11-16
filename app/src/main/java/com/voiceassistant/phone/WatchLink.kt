package com.voiceassistant.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.voiceassistant.phone.watch.WatchProtocol
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        if (!enabled || !::context.isInitialized) {
            android.util.Log.i("VAWatch", "not sending $path: enabled=$enabled")
            return
        }
        runCatching {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                android.util.Log.i("VAWatch", "sending $path to ${nodes.map { it.displayName }}")
                val messages = Wearable.getMessageClient(context)
                nodes.forEach { messages.sendMessage(it.id, path, data).addOnFailureListener { e -> android.util.Log.w("VAWatch", "send $path failed", e) } }
            }.addOnFailureListener { android.util.Log.w("VAWatch", "no node list", it) }
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

    // ---- the spoken reply, played on the watch ("Where replies play when I ask from my watch": Watch) ----------------------------------
    // Everything below runs in order on one worker thread, so the channel is opened, written and closed without ever blocking the screen
    // or the PC link. [audioGeneration] works like the phone's own Player: a cancel makes every piece queued before it a no-op.
    private val audioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "watch-reply-audio").also { it.isDaemon = true } }
    private val audioGeneration = AtomicInteger()
    private val nextAudioId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt() + 1)
    @Volatile private var audioChannel: ChannelClient.Channel? = null
    private var audioOut: OutputStream? = null
    @Volatile private var audioId = 0
    @Volatile private var audioNode: String? = null
    private var audioRate = 0

    /** A sentence of the reply is about to arrive at [sampleRate]; the first one opens the channel to the watch ([nodeId]) that asked. */
    fun replyAudioStart(nodeId: String, sampleRate: Int) {
        if (!enabled || !::context.isInitialized) return
        val gen = audioGeneration.get()
        audioWorker.execute {
            if (gen != audioGeneration.get()) return@execute
            if (audioOut == null && !openAudio(nodeId)) return@execute
            if (sampleRate != audioRate) {
                audioRate = sampleRate
                writeAudio(WatchProtocol.ReplyAudio.start(audioId, sampleRate))
            }
        }
    }

    fun replyAudio(pcm: ByteArray) {
        val gen = audioGeneration.get()
        audioWorker.execute {
            if (gen != audioGeneration.get() || audioOut == null) return@execute
            WatchProtocol.ReplyAudio.pcm(pcm).forEach { if (!writeAudio(it)) return@execute }
        }
    }

    /** The reply is over: the watch plays what it has, then the channel closes. */
    fun endReplyAudio() {
        val gen = audioGeneration.get()
        audioWorker.execute {
            if (gen != audioGeneration.get() || audioOut == null) return@execute
            writeAudio(WatchProtocol.ReplyAudio.end())
            closeAudio()
        }
    }

    /** Stop the reply on the watch at once (Stop, a new question, or the PC cut it off). The message overtakes audio still in the channel. */
    fun cancelReplyAudio() {
        audioGeneration.incrementAndGet()
        val id = audioId
        val node = audioNode
        if (id != 0 && node != null && ::context.isInitialized) {
            runCatching { Wearable.getMessageClient(context).sendMessage(node, WatchProtocol.REPLY_AUDIO_CANCEL, WatchProtocol.encodeCancel(id)) }
        }
        // closing the channel here, not only on the worker, frees a worker that is stuck writing to a watch that has gone out of reach
        audioChannel?.let { channel -> runCatching { Wearable.getChannelClient(context).close(channel) } }
        audioWorker.execute { closeAudio() }
    }

    private fun openAudio(nodeId: String): Boolean = try {
        val client = Wearable.getChannelClient(context)
        val channel = Tasks.await(client.openChannel(nodeId, WatchProtocol.REPLY_AUDIO_CHANNEL), 5, TimeUnit.SECONDS)
        audioChannel = channel
        audioOut = Tasks.await(client.getOutputStream(channel), 5, TimeUnit.SECONDS)
        audioRate = 0
        audioNode = nodeId
        audioId = nextAudioId.getAndIncrement()
        android.util.Log.i("VAWatch", "reply audio channel $audioId open")
        true
    } catch (e: Exception) {
        android.util.Log.w("VAWatch", "could not open the reply audio channel", e)
        closeAudio()
        false
    }

    private fun writeAudio(bytes: ByteArray): Boolean = try {
        audioOut?.write(bytes)
        true
    } catch (e: java.io.IOException) {
        android.util.Log.w("VAWatch", "reply audio stopped: ${e.message}") // the watch closed it (Stop, Talk) or went out of reach
        closeAudio()
        false
    }

    private fun closeAudio() {
        runCatching { audioOut?.flush() }
        runCatching { audioOut?.close() }
        audioChannel?.let { channel -> runCatching { Wearable.getChannelClient(context).close(channel) } }
        audioOut = null
        audioChannel = null
        audioRate = 0
        audioId = 0
    }
}
