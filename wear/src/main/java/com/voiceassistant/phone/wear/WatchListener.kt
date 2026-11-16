package com.voiceassistant.phone.wear

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.voiceassistant.phone.watch.WatchProtocol

/** What the phone sends the watch: its state, what it heard, the reply as it grows, questions that need yes or no, and the spoken reply. */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WatchModel.heardFromPhone()
        when (event.path) {
            WatchProtocol.STATE -> WatchProtocol.decodeState(event.data)?.let {
                if (it.phase == "listening" || it.phase == WatchProtocol.PHASE_OFF) ReplyPlayer.stop() // a new question, or switched off: the old reply is over
                WatchModel.onState(it)
            }
            WatchProtocol.HEARD -> WatchModel.onHeard(WatchProtocol.decodeText(event.data))
            WatchProtocol.REPLY -> WatchProtocol.decodeReply(event.data)?.let { WatchModel.onReply(it) }
            WatchProtocol.ASK -> WatchProtocol.decodeAsk(event.data)?.let { WatchModel.onAsk(it) }
            WatchProtocol.REPLY_AUDIO_CANCEL -> WatchProtocol.decodeCancel(event.data)?.let { ReplyPlayer.cancel(it) }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchProtocol.REPLY_AUDIO_CHANNEL) return
        WatchModel.heardFromPhone()
        if (!ReplyPlayer.hasSpeaker(this)) { // no speaker: the reply shows as text only
            runCatching { Wearable.getChannelClient(applicationContext).close(channel) }
            return
        }
        ReplyPlayer.play(applicationContext, channel)
    }
}
