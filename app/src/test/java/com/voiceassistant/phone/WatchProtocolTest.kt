package com.voiceassistant.phone

import com.voiceassistant.phone.watch.WatchProtocol
import com.voiceassistant.phone.watch.WatchProtocol.Ask
import com.voiceassistant.phone.watch.WatchProtocol.Framer
import com.voiceassistant.phone.watch.WatchProtocol.Reply
import com.voiceassistant.phone.watch.WatchProtocol.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    @Test fun stateRoundTrips() {
        val s = State("thinking", connected = true, offlineHelper = false)
        assertEquals(s, WatchProtocol.decodeState(WatchProtocol.encode(s)))
    }

    @Test fun replyRoundTripsAndIsCapped() {
        val r = Reply("The capital of Australia is Canberra.", done = true)
        assertEquals(r, WatchProtocol.decodeReply(WatchProtocol.encode(r)))
        val long = WatchProtocol.decodeReply(WatchProtocol.encode(Reply("x".repeat(10_000), false)))!!
        assertEquals(WatchProtocol.MAX_TEXT_CHARS, long.text.length)
    }

    @Test fun askRoundTripsAndNeedsAPrompt() {
        val a = Ask("c1", "Set an alarm for 06:30?", "action")
        assertEquals(a, WatchProtocol.decodeAsk(WatchProtocol.encode(a)))
        assertNull(WatchProtocol.decodeAsk("""{"id":"x","prompt":""}""".toByteArray()))
    }

    @Test fun answersMustSayYesOrNo() {
        assertEquals(true, WatchProtocol.decodeAnswer(WatchProtocol.encodeAnswer(true)))
        assertEquals(false, WatchProtocol.decodeAnswer(WatchProtocol.encodeAnswer(false)))
        assertNull(WatchProtocol.decodeAnswer("{}".toByteArray())) // nothing is approved by an empty or broken message
        assertNull(WatchProtocol.decodeAnswer("not json".toByteArray()))
    }

    @Test fun garbageFromTheLinkDoesNotCrash() {
        assertNull(WatchProtocol.decodeState(byteArrayOf(0, 1, 2)))
        assertNull(WatchProtocol.decodeReply("[1,2".toByteArray()))
        assertEquals("", WatchProtocol.decodeText(ByteArray(0)))
        assertEquals("hello", WatchProtocol.decodeText("  hello \n".toByteArray()))
    }

    @Test fun audioIsCutIntoWholeFramesWhateverTheChannelDelivers() {
        val framer = Framer(frameBytes = 960)
        assertTrue(framer.push(ByteArray(500)).isEmpty())
        val first = framer.push(ByteArray(1500)) // 2000 bytes in total: two frames and 80 left over
        assertEquals(2, first.size)
        assertTrue(first.all { it.size == 960 })
        assertEquals(1, framer.push(ByteArray(880)).size) // the 80 left over plus 880 is exactly one more
    }

    @Test fun framesKeepTheirBytesInOrder() {
        val framer = Framer(frameBytes = 4)
        val out = framer.push(byteArrayOf(1, 2, 3)) + framer.push(byteArrayOf(4, 5, 6, 7, 8, 9))
        assertEquals(listOf(listOf<Byte>(1, 2, 3, 4), listOf<Byte>(5, 6, 7, 8)), out.map { it.toList() })
    }

    @Test fun aFrameIsThirtyMilliseconds() {
        assertEquals(960, WatchProtocol.FRAME_BYTES)
    }
}
