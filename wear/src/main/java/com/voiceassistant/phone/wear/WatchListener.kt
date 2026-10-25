package com.voiceassistant.phone.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.voiceassistant.phone.watch.WatchProtocol

/** What the phone sends the watch: its state, what it heard, the reply as it grows, and questions that need yes or no. */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WatchProtocol.STATE -> WatchProtocol.decodeState(event.data)?.let { WatchModel.onState(it) }
            WatchProtocol.HEARD -> WatchModel.onHeard(WatchProtocol.decodeText(event.data))
            WatchProtocol.REPLY -> WatchProtocol.decodeReply(event.data)?.let { WatchModel.onReply(it) }
            WatchProtocol.ASK -> WatchProtocol.decodeAsk(event.data)?.let { WatchModel.onAsk(it) }
        }
    }
}
