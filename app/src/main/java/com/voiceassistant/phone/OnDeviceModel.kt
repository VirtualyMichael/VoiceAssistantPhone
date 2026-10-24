package com.voiceassistant.phone

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * An AI model that runs on the phone itself (Gemma, with Google's LiteRT-LM), so simple things could still work when the PC is off or out of reach.
 * Used by [OfflineHelper] when the PC is out of reach, and measured from debug builds with [DebugEval]. The model file is not part of the app:
 * the user downloads it once from Settings (see [download]), or a developer copies it to files/models/ with adb.
 */
object OnDeviceModel {
    private var engine: Engine? = null
    private var loadedMs = 0L
    private var backendName = ""
    private val lock = Mutex()

    fun modelFile(context: Context): File? =
        File(context.getExternalFilesDir(null), "models").listFiles { f -> f.name.endsWith(".litertlm") }?.maxByOrNull { it.length() }

    fun available(context: Context) = modelFile(context) != null

    fun status(context: Context): String {
        val f = modelFile(context) ?: return "no model file in ${File(context.getExternalFilesDir(null), "models")}"
        return "${f.name} ${f.length() / 1_000_000} MB, " + if (engine != null) "loaded on $backendName in $loadedMs ms" else "not loaded"
    }

    /** Load the model (slow the first time); GPU when it works, else CPU. */
    private fun load(context: Context, gpu: Boolean): Engine {
        engine?.let { return it }
        val file = modelFile(context) ?: throw IllegalStateException("no model file")
        val start = System.currentTimeMillis()
        val made = runCatching {
            Engine(EngineConfig(modelPath = file.path, backend = if (gpu) Backend.GPU() else Backend.CPU(), cacheDir = context.cacheDir.path)).also { it.initialize() }
                .also { backendName = if (gpu) "GPU" else "CPU" }
        }.getOrElse { e ->
            if (!gpu) throw e
            Log.w("VA_EVAL", "GPU failed (${e.message}), trying the CPU")
            Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = context.cacheDir.path)).also { it.initialize() }
                .also { backendName = "CPU" }
        }
        loadedMs = System.currentTimeMillis() - start
        engine = made
        return made
    }

    /** One answer in a fresh conversation, and how long it took in milliseconds. */
    suspend fun ask(context: Context, prompt: String, system: String = "", gpu: Boolean = true): Pair<String, Long> = lock.withLock {
        val e = load(context, gpu)
        val start = System.currentTimeMillis()
        val config = if (system.isBlank()) ConversationConfig() else ConversationConfig(systemInstruction = Contents.of(system))
        val text = e.createConversation(config).use { c ->
            c.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
        text to (System.currentTimeMillis() - start)
    }

    const val DOWNLOAD_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm"
    const val DOWNLOAD_BYTES = 2_008_432_640L
    const val DOWNLOAD_TEXT = "1.9 GB"

    /** Fetch the model into the app's files/models/ (continues a stopped download). [progress] gets 0..100; returns null when done, else the problem. Blocking: call off the main thread. */
    fun download(context: Context, progress: (Int) -> Unit): String? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
        val target = File(dir, "gemma-4-E2B-it-gpu.litertlm")
        if (target.length() == DOWNLOAD_BYTES) return@runCatching null
        val part = File(dir, target.name + ".part")
        val have = part.length()
        val connection = (java.net.URL(DOWNLOAD_URL).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        val resumed = connection.responseCode == 206
        if (connection.responseCode != 200 && !resumed) return@runCatching "The download site said ${connection.responseCode}."
        val start = if (resumed) have else 0L
        java.io.FileOutputStream(part, resumed).use { out ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                var done = start
                var lastPercent = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    val percent = (done * 100 / DOWNLOAD_BYTES).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                }
            }
        }
        if (part.length() != DOWNLOAD_BYTES) return@runCatching "The download stopped early. Try again to continue it."
        if (!part.renameTo(target)) return@runCatching "Could not save the model."
        null
    }.getOrElse { "The download failed (${it.message}). Try again to continue it." }

    fun unload() {
        engine?.close()
        engine = null
    }
}

/**
 * Developer hook for measuring the on-device model with the PC's test set (debug builds only, see MainActivity):
 *   adb shell am start -n com.voiceassistant.phone/.MainActivity --es eval_id ID --es eval_prompt_b64 BASE64 [--es eval_system_b64 BASE64] [--ez eval_cpu true]
 *   adb shell am start -n com.voiceassistant.phone/.MainActivity --es eval_id ID --ez eval_status true
 * Each result is one log line under the tag VA_EVAL: "ID ok|error MILLISECONDS BASE64(text)".
 */
object DebugEval {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun handle(context: Context, intent: android.content.Intent) {
        val id = intent.getStringExtra("eval_id") ?: return
        val app = context.applicationContext
        scope.launch {
            val (ok, ms, text) = runCatching {
                if (intent.getBooleanExtra("eval_status", false)) Triple(true, 0L, OnDeviceModel.status(app))
                else {
                    val prompt = decode(intent.getStringExtra("eval_prompt_b64"))
                    val (answer, took) = OnDeviceModel.ask(app, prompt, decode(intent.getStringExtra("eval_system_b64")), gpu = !intent.getBooleanExtra("eval_cpu", false))
                    Triple(true, took, answer)
                }
            }.getOrElse { Triple(false, 0L, "${it.javaClass.simpleName}: ${it.message}") }
            Log.i(TAG, "$id ${if (ok) "ok" else "error"} $ms ${Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)}")
        }
    }

    private fun decode(b64: String?) = if (b64.isNullOrBlank()) "" else String(Base64.decode(b64, Base64.DEFAULT))
    private const val TAG = "VA_EVAL"
}
