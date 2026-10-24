package com.voiceassistant.phone

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Wake phrase and voice detection that run on the phone (sherpa-onnx). The wake model is the same small model the PC uses; the PC turns the
 * phrase into the model's token format ([keywordLines]) because the tokenizer file lives there.
 */
class SherpaDetectors(context: Context, private var keywordLines: String) : Detectors {
    private val kws: KeywordSpotter
    private var stream: OnlineStream
    private val vad: Vad

    init {
        val assets = context.assets
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                joiner = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            ),
            tokens = "kws/tokens.txt",
            numThreads = 1,
            provider = "cpu",
            modelType = "zipformer2",
        )
        kws = KeywordSpotter(
            assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = model,
                keywordsFile = "kws/keywords.txt", // a placeholder; the real phrase is given to each stream below
                keywordsScore = 1.5f,
                keywordsThreshold = 0.12f,
            ),
        )
        stream = kws.createStream(keywordLines)
        vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.1f, windowSize = 512, maxSpeechDuration = 30f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            ),
        )
    }

    /** A new wake phrase (or sensitivity): the PC sent fresh token lines. */
    @Synchronized
    fun setKeywords(lines: String) {
        keywordLines = lines
        resetWake()
    }

    @Synchronized
    override fun wakeWord(samples: FloatArray): Boolean {
        stream.acceptWaveform(samples, 16000)
        var hit = false
        while (kws.isReady(stream)) {
            kws.decode(stream)
            if (kws.getResult(stream).keyword.isNotEmpty()) {
                hit = true
                kws.reset(stream)
            }
        }
        return hit
    }

    @Synchronized
    override fun speaking(samples: FloatArray): Boolean {
        vad.acceptWaveform(samples)
        val speech = vad.isSpeechDetected()
        while (!vad.empty()) vad.pop() // only the live "is someone talking" flag matters, not the segments
        return speech
    }

    /** A fresh stream forgets what was said before, so a phrase that was just spoken cannot trigger twice. */
    @Synchronized
    override fun resetWake() {
        stream.release()
        stream = kws.createStream(keywordLines)
    }

    @Synchronized
    override fun resetSpeech() = vad.reset()

    @Synchronized
    fun release() {
        stream.release()
        kws.release()
        vad.release()
    }
}
