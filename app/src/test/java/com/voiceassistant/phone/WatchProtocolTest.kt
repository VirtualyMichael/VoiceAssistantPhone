package com.voiceassistant.phone

import com.voiceassistant.phone.watch.WatchProtocol
import com.voiceassistant.phone.watch.WatchProtocol.Ask
import com.voiceassistant.phone.watch.WatchProtocol.Framer
import com.voiceassistant.phone.watch.WatchProtocol.Hello
import com.voiceassistant.phone.watch.WatchProtocol.Reply
import com.voiceassistant.phone.watch.WatchProtocol.ReplyAudio
import com.voiceassistant.phone.watch.WatchProtocol.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ---- hello, cancel ----

    @Test fun helloSaysWhetherTheWatchHasASpeaker() {
        assertEquals(Hello(true), WatchProtocol.decodeHello(WatchProtocol.encode(Hello(true))))
        assertEquals(Hello(false), WatchProtocol.decodeHello(WatchProtocol.encode(Hello(false))))
        assertEquals(Hello(false), WatchProtocol.decodeHello(ByteArray(0))) // an old watch app: no audio is sent to it
        assertEquals(Hello(false), WatchProtocol.decodeHello("{{".toByteArray()))
    }

    @Test fun cancelNamesTheReplyOrNothing() {
        assertEquals(7, WatchProtocol.decodeCancel(WatchProtocol.encodeCancel(7)))
        assertNull(WatchProtocol.decodeCancel("{}".toByteArray()))
        assertNull(WatchProtocol.decodeCancel("""{"id":"x"}""".toByteArray()))
        assertNull(WatchProtocol.decodeCancel(byteArrayOf(1, 2, 3)))
    }

    // ---- reply audio, phone to watch ----

    private fun stream(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    @Test fun replyAudioRoundTrips() {
        val pcm = ByteArray(3000) { (it % 251).toByte() }
        val bytes = stream(ReplyAudio.start(5, 24_000), *ReplyAudio.pcm(pcm).toTypedArray(), ReplyAudio.end())
        val reader = ReplyAudio.Reader()
        val pieces = reader.push(bytes)
        assertEquals(3, pieces.size)
        assertEquals(ReplyAudio.Start(5, 24_000), pieces[0])
        assertArrayEquals(pcm, (pieces[1] as ReplyAudio.Pcm).bytes)
        assertSame(ReplyAudio.End, pieces[2])
        assertTrue(reader.ended)
        assertFalse(reader.broken)
    }

    @Test fun longAudioIsCutIntoPiecesThatFit() {
        val pcm = ByteArray(ReplyAudio.MAX_PIECE * 2 + 10)
        val pieces = ReplyAudio.pcm(pcm)
        assertEquals(3, pieces.size)
        val reader = ReplyAudio.Reader()
        val back = reader.push(stream(ReplyAudio.start(1, 16_000), *pieces.toTypedArray()))
        assertEquals(pcm.size, back.filterIsInstance<ReplyAudio.Pcm>().sumOf { it.bytes.size })
    }

    @Test fun aPieceSplitAcrossChunksWaitsForTheRest() {
        val bytes = stream(ReplyAudio.start(2, 22_050), *ReplyAudio.pcm(ByteArray(100) { 9 }).toTypedArray(), ReplyAudio.end())
        val reader = ReplyAudio.Reader()
        val got = ArrayList<ReplyAudio.Piece>()
        for (b in bytes) got += reader.push(byteArrayOf(b)) // one byte at a time, the worst a channel can do
        assertEquals(3, got.size)
        assertEquals(ReplyAudio.Start(2, 22_050), got[0])
        assertEquals(100, (got[1] as ReplyAudio.Pcm).bytes.size)
        assertSame(ReplyAudio.End, got[2])
    }

    @Test fun aPartialPieceAloneGivesNothingYet() {
        val start = ReplyAudio.start(3, 16_000)
        val reader = ReplyAudio.Reader()
        assertTrue(reader.push(start.copyOf(7)).isEmpty())
        assertFalse(reader.broken)
        assertEquals(listOf(ReplyAudio.Start(3, 16_000)), reader.push(start.copyOfRange(7, start.size)))
    }

    @Test fun garbageAudioStopsTheStream() {
        val unknownKind = ReplyAudio.Reader()
        assertTrue(unknownKind.push(byteArrayOf(9, 0, 0, 0, 1, 0)).isEmpty())
        assertTrue(unknownKind.broken)
        assertTrue(unknownKind.push(stream(ReplyAudio.start(1, 16_000))).isEmpty()) // broken stays broken

        val tooBig = ReplyAudio.Reader()
        tooBig.push(ReplyAudio.start(1, 16_000))
        assertTrue(tooBig.push(byteArrayOf(ReplyAudio.KIND_PCM, 0x7F, 0, 0, 0)).isEmpty())
        assertTrue(tooBig.broken)

        val audioFirst = ReplyAudio.Reader()
        assertTrue(audioFirst.push(ReplyAudio.pcm(ByteArray(10))[0]).isEmpty())
        assertTrue(audioFirst.broken)

        val silly = ReplyAudio.Reader()
        assertTrue(silly.push(ReplyAudio.start(1, 1_000_000)).isEmpty())
        assertTrue(silly.broken)

        val text = ReplyAudio.Reader()
        text.push("""{"text":"hello"}""".toByteArray())
        assertTrue(text.broken)
    }

    @Test fun nothingIsReadAfterTheEnd() {
        val reader = ReplyAudio.Reader()
        val pieces = reader.push(stream(ReplyAudio.start(1, 16_000), ReplyAudio.end(), *ReplyAudio.pcm(ByteArray(10)).toTypedArray()))
        assertEquals(2, pieces.size)
        assertTrue(reader.push(ReplyAudio.pcm(ByteArray(10))[0]).isEmpty())
    }

    @Test fun theSampleRateMayChangeMidReply() {
        val reader = ReplyAudio.Reader()
        val pieces = reader.push(stream(ReplyAudio.start(4, 16_000), *ReplyAudio.pcm(ByteArray(4)).toTypedArray(), ReplyAudio.start(4, 24_000)))
        assertEquals(ReplyAudio.Start(4, 24_000), pieces.last())
        assertFalse(reader.broken)
    }
}
