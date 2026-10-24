package com.voiceassistant.phone

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Where to connect, and how to prove who we are. */
sealed interface Target {
    val host: String
    val port: Int
}

/** A one-time code from the PC's pairing screen; the PC answers with the phone's own permanent token. */
data class PairRequest(override val host: String, override val port: Int, val code: String, val name: String) : Target

/** A paired PC: where it is and the token that proves this phone was paired. */
data class Pairing(override val host: String, override val port: Int, val token: String, val name: String) : Target {
    fun toJson(): String = JSONObject()
        .put("host", host).put("port", port).put("token", token).put("name", name).toString()

    companion object {
        fun parse(text: String): Pairing? = try {
            val o = JSONObject(text.trim())
            Pairing(o.getString("host"), o.getInt("port"), o.getString("token"), o.optString("name", "").ifBlank { "My PC" })
        } catch (e: Exception) {
            null
        }
    }
}

interface RemoteListener {
    fun onReady(message: JSONObject)
    fun onPaired(message: JSONObject)
    fun onRefused(code: String, message: String)
    fun onMessage(message: JSONObject)
    fun onAudio(pcm: ByteArray)
    fun onClosed(reason: String)
}

/** The WebSocket to the PC's assistant (see remote.py on the PC for the message list). */
class RemoteClient(private val target: Target, private val listener: RemoteListener) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
        val request = Request.Builder().url("ws://${target.host}:${target.port}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject().put("type", "hello").put("device", android.os.Build.MODEL)
                when (val t = target) {
                    is Pairing -> hello.put("token", t.token)
                    is PairRequest -> hello.put("pair_code", t.code)
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try { JSONObject(text) } catch (e: Exception) { return }
                when (message.optString("type")) {
                    "ready" -> listener.onReady(message)
                    "paired" -> listener.onPaired(message)
                    "error" -> if (message.has("code")) listener.onRefused(message.optString("code"), message.optString("message")) else listener.onMessage(message)
                    else -> listener.onMessage(message)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onAudio(bytes.toByteArray())

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(webSocket, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(webSocket, t.message ?: "connection failed")
        })
    }

    private fun end(webSocket: WebSocket, reason: String) {
        if (webSocket === socket && !closed) {
            closed = true
            listener.onClosed(reason)
        }
    }

    fun close() {
        closed = true
        socket?.close(1000, "bye")
        socket = null
    }

    private fun send(message: JSONObject) { socket?.send(message.toString()) }

    fun sendAudio(pcm: ByteArray) { socket?.send(pcm.toByteString()) }
    fun wake() = send(JSONObject().put("type", "wake"))
    fun endOfSpeech() = send(JSONObject().put("type", "end_of_speech"))
    fun interrupt() = send(JSONObject().put("type", "interrupt"))
    fun getKeywords(phrase: String, threshold: Float) =
        send(JSONObject().put("type", "get_keywords").put("phrase", phrase).put("threshold", threshold.toDouble()))
    fun cameraPending(id: String, opened: Boolean) =
        send(JSONObject().put("type", "camera_pending").put("id", id).put("opened", opened))
    fun cameraPhoto(id: String, jpegBase64: String) = send(JSONObject().put("type", "camera_photo").put("id", id).put("jpeg_b64", jpegBase64))
    fun cameraVideoBegin(id: String, count: Int, durationMs: Long, detailCount: Int = 0) =
        send(JSONObject().put("type", "camera_video_begin").put("id", id).put("count", count).put("duration_ms", durationMs).put("detail_count", detailCount))
    fun cameraVideoFrame(id: String, index: Int, timeMs: Long, jpegBase64: String, detail: Boolean = false) =
        send(JSONObject().put("type", "camera_video_frame").put("id", id).put("index", index).put("t_ms", timeMs).put("jpeg_b64", jpegBase64).put("detail", detail))
    fun cameraVideoAudio(id: String, pcmBase64: String) = send(JSONObject().put("type", "camera_video_audio").put("id", id).put("pcm_b64", pcmBase64))
    fun cameraVideoEnd(id: String) = send(JSONObject().put("type", "camera_video_end").put("id", id))
    /** Bytes waiting to be sent. OkHttp drops the connection past 16 MB, so a clip's frames are sent only while this is small. */
    fun queuedBytes(): Long = socket?.queueSize() ?: 0L
    fun cameraCancel(id: String, reason: String) = send(JSONObject().put("type", "camera_cancel").put("id", id).put("reason", reason))
    fun forgetMe() = send(JSONObject().put("type", "forget_me"))
    fun getSettings() = send(JSONObject().put("type", "get_settings"))
    fun setSetting(key: String, value: Any) = send(JSONObject().put("type", "set_setting").put("key", key).put("value", value))
    fun sendText(text: String) = send(JSONObject().put("type", "text").put("text", text))
    fun confirmResult(id: String, ok: Boolean) = send(JSONObject().put("type", "confirm_result").put("id", id).put("ok", ok))
}
