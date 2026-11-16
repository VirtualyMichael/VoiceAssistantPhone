package com.voiceassistant.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.voiceassistant.phone.watch.WatchProtocol

/**
 * Receives what the watch app sends: button presses, the spoken question (as an audio channel), typed messages and yes/no answers, and hands
 * them to the same [Controller] the phone's own screen uses. Ignored completely unless the user switched the watch on in Settings: nothing the
 * watch sends is acted on, and the assistant is not even started for it. The one exception is the watch's hello, which gets a fixed "switched
 * off" answer (see [answerSwitchedOff]) so the watch can say where to turn it on instead of "Phone not found".
 */
class WatchService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    /** Read straight from the saved setting, so a watch message never starts the assistant (and its link to the PC) while switched off. */
    private fun switchedOn(): Boolean = getSharedPreferences("voice", Context.MODE_PRIVATE).getBoolean("watch", false)

    private fun allowed(): Boolean {
        if (!switchedOn()) return false
        Controller.init(applicationContext)
        WatchLink.init(applicationContext)
        return Controller.watchEnabled
    }

    override fun onMessageReceived(event: MessageEvent) {
        val on = allowed()
        android.util.Log.i("VAWatch", "message ${event.path} from the watch; switched on in Settings: $on")
        if (!on) {
            if (event.path == WatchProtocol.HELLO) answerSwitchedOff(event.sourceNodeId)
            return
        }
        val node = event.sourceNodeId
        main.post {
            when (event.path) {
                WatchProtocol.HELLO -> Controller.watchHello(node, WatchProtocol.decodeHello(event.data))
                WatchProtocol.TALK_START -> Controller.watchTalkStart(node, WatchProtocol.decodeHello(event.data))
                WatchProtocol.TALK_END -> Controller.watchTalkEnd()
                WatchProtocol.QUICK -> WatchProtocol.decodeText(event.data).takeIf { it.isNotBlank() }?.let { Controller.watchQuick(it, node) }
                WatchProtocol.ANSWER -> WatchProtocol.decodeAnswer(event.data)?.let { Controller.watchAnswer(it) }
                WatchProtocol.NEW_CHAT -> Controller.newChat()
                WatchProtocol.STOP -> Controller.stopSpeaking()
            }
        }
    }

    /**
     * The only thing ever sent to a watch while the setting is off, and only to the watch that just said hello: the fixed phase "off". It carries
     * nothing about the assistant, the PC or any conversation, and nothing the watch sends is acted on, so the watch stays ignored.
     */
    private fun answerSwitchedOff(node: String) {
        val off = WatchProtocol.encode(WatchProtocol.State(WatchProtocol.PHASE_OFF, connected = false, offlineHelper = false))
        runCatching { Wearable.getMessageClient(applicationContext).sendMessage(node, WatchProtocol.STATE, off) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchProtocol.AUDIO_CHANNEL) return
        val client = Wearable.getChannelClient(applicationContext)
        if (!allowed()) {
            runCatching { client.close(channel) } // switched off: the watch's microphone stream is refused, not read
            return
        }
        Thread {
            runCatching {
                val stream = Tasks.await(client.getInputStream(channel))
                val framer = WatchProtocol.Framer()
                val buffer = ByteArray(4096)
                stream.use {
                    while (true) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        for (frame in framer.push(buffer, n)) Controller.watchAudio(frame)
                    }
                }
            }
            runCatching { client.close(channel) }
            Controller.watchAudioEnded() // if the watch went away mid-question, the question still ends instead of listening on
        }.also { it.isDaemon = true; it.start() }
    }
}
