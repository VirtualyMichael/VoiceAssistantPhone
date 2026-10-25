package com.voiceassistant.phone

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
 * them to the same [Controller] the phone's own screen uses. Ignored completely unless the user switched the watch on in Settings.
 */
class WatchService : WearableListenerService() {
    private val main = Handler(Looper.getMainLooper())

    private fun allowed(): Boolean {
        Controller.init(applicationContext)
        WatchLink.init(applicationContext)
        return Controller.watchEnabled
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (!allowed()) return
        main.post {
            when (event.path) {
                WatchProtocol.HELLO -> Controller.watchHello()
                WatchProtocol.TALK_START -> Controller.watchTalkStart()
                WatchProtocol.TALK_END -> Controller.watchTalkEnd()
                WatchProtocol.QUICK -> WatchProtocol.decodeText(event.data).takeIf { it.isNotBlank() }?.let { Controller.sendText(it) }
                WatchProtocol.ANSWER -> WatchProtocol.decodeAnswer(event.data)?.let { Controller.watchAnswer(it) }
                WatchProtocol.NEW_CHAT -> Controller.newChat()
                WatchProtocol.STOP -> Controller.stopSpeaking()
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchProtocol.AUDIO_CHANNEL || !allowed()) return
        val client = Wearable.getChannelClient(applicationContext)
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
        }.also { it.isDaemon = true; it.start() }
    }
}
