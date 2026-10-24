package com.voiceassistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeDetectors : Detectors {
    var wake = false
    var speech = false
    var wakeResets = 0
    override fun wakeWord(samples: FloatArray): Boolean = wake.also { wake = false }
    override fun speaking(samples: FloatArray): Boolean = speech
    override fun resetWake() { wakeResets++ }
    override fun resetSpeech() {}
}

private class FakeSink : HandsFreeSink {
    val events = mutableListOf<String>()
    var audioFrames = 0
    override fun sendAudio(frame: ByteArray) { audioFrames++ }
    override fun wake() { events += "wake" }
    override fun endOfSpeech() { events += "end" }
    override fun interrupt() { events += "interrupt" }
    override fun flushPlayback() { events += "flush" }
    override fun changed() {}
    override fun noSpeech() { events += "nospeech" }
}

class HandsFreeEngineTest {
    private lateinit var detectors: FakeDetectors
    private lateinit var sink: FakeSink
    private lateinit var engine: HandsFreeEngine
    private var now = 0L
    private val frame = ByteArray(960) // 30 ms of silence

    @Before
    fun setUp() {
        detectors = FakeDetectors()
        sink = FakeSink()
        now = 1_000L
        engine = HandsFreeEngine(detectors, sink) { now }
        engine.start()
    }

    private fun feed(ms: Long, speech: Boolean = false) {
        var t = 0L
        while (t < ms) {
            now += 30
            t += 30
            detectors.speech = speech
            engine.process(frame)
        }
    }

    private fun hearWakePhrase() {
        detectors.wake = true
        now += 30
        engine.process(frame)
    }

    @Test
    fun nothingLeavesThePhoneUntilTheWakePhraseIsHeard() {
        feed(5_000, speech = true)
        assertEquals(HfState.Armed, engine.state)
        assertTrue(sink.events.isEmpty())
        assertEquals(0, sink.audioFrames)
    }

    @Test
    fun wakePhraseStartsATurnAndSendsTheAudioJustBeforeIt() {
        feed(600)
        hearWakePhrase()
        assertEquals(HfState.Capturing, engine.state)
        assertEquals(listOf("wake"), sink.events)
        assertEquals(HandsFreeEngine.PRE_ROLL_SENT_FRAMES, sink.audioFrames)
    }

    @Test
    fun theTurnEndsAfterSilenceFollowingSpeech() {
        hearWakePhrase()
        feed(400) // a pause right after the wake phrase must not end the turn
        feed(1_500, speech = true)
        assertEquals(HfState.Capturing, engine.state)
        feed(600)
        assertEquals(HfState.Capturing, engine.state)
        feed(500)
        assertEquals(HfState.Waiting, engine.state)
        assertEquals(listOf("wake", "end"), sink.events)
    }

    @Test
    fun theTailOfTheWakePhraseAloneDoesNotCountAsSpeech() {
        hearWakePhrase()
        feed(240, speech = true) // inside the grace period
        feed(HandsFreeEngine.NO_SPEECH_MS, speech = false)
        assertEquals(HfState.Armed, engine.state)
        assertEquals(listOf("wake", "interrupt", "nospeech"), sink.events)
    }

    @Test
    fun saidNothingAfterTheWakePhraseGivesUp() {
        hearWakePhrase()
        feed(HandsFreeEngine.NO_SPEECH_MS + 100)
        assertEquals(HfState.Armed, engine.state)
        assertEquals(listOf("wake", "interrupt", "nospeech"), sink.events)
    }

    @Test
    fun theWakePhraseInterruptsAReply() {
        hearWakePhrase()
        feed(500, speech = true)
        feed(1_000)
        assertEquals(HfState.Waiting, engine.state)
        sink.events.clear()
        hearWakePhrase()
        assertEquals(listOf("flush", "interrupt", "wake"), sink.events)
        assertEquals(HfState.Capturing, engine.state)
    }

    @Test
    fun theTurnIsOverWhenThePcGoesIdle() {
        hearWakePhrase()
        feed(500, speech = true)
        feed(1_000)
        assertEquals(HfState.Waiting, engine.state)
        engine.onPcState("speaking")
        assertEquals(HfState.Waiting, engine.state)
        engine.onPcState("idle")
        assertEquals(HfState.Armed, engine.state)
    }

    @Test
    fun anIdleReportRightAfterTheWakePhraseIsIgnored() {
        hearWakePhrase()
        engine.onPcState("idle") // the PC reports idle while it hands over from a cut-off reply
        assertEquals(HfState.Capturing, engine.state)
        feed(HandsFreeEngine.PC_GIVE_UP_AFTER_MS + 100)
        engine.onPcState("idle")
        assertEquals(HfState.Armed, engine.state)
    }

    @Test
    fun theTalkButtonStartsATurnAndLettingGoEndsIt() {
        engine.manualPress()
        assertEquals(HfState.Capturing, engine.state)
        feed(5_000, speech = false) // no voice detection while the button is held
        assertEquals(HfState.Capturing, engine.state)
        engine.manualRelease()
        feed(HandsFreeEngine.RELEASE_TAIL_MS + 60)
        assertEquals(HfState.Waiting, engine.state)
        assertEquals(listOf("wake", "end"), sink.events)
    }

    @Test
    fun losingThePcReturnsToListeningForTheWakePhrase() {
        hearWakePhrase()
        engine.onDisconnected()
        assertEquals(HfState.Armed, engine.state)
    }

    @Test
    fun stoppingDuringATurnTellsThePcToGoBackToIdle() {
        hearWakePhrase()
        engine.stop()
        assertEquals(HfState.Off, engine.state)
        assertEquals(listOf("wake", "interrupt"), sink.events)
        val sent = sink.audioFrames
        feed(1_000)
        assertEquals(sent, sink.audioFrames) // nothing more is sent once it is off
    }
}
