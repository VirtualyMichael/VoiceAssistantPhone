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

/** What the PC's pairing step gives us: where the PC is and the one-time token that proves this phone was paired. */
data class Pairing(val host: String, val port: Int, val token: String, val name: String) {
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
    fun onMessage(message: JSONObject)
    fun onAudio(pcm: ByteArray)
    fun onClosed(reason: String)
}

/** The WebSocket to the PC's assistant (see remote.py on the PC for the message list). */
class RemoteClient(private val pairing: Pairing, private val listener: RemoteListener) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
        val request = Request.Builder().url("ws://${pairing.host}:${pairing.port}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "hello").put("token", pairing.token).put("device", android.os.Build.MODEL).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try { JSONObject(text) } catch (e: Exception) { return }
                if (message.optString("type") == "ready") listener.onReady(message) else listener.onMessage(message)
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
    fun cameraCancel(id: String, reason: String) = send(JSONObject().put("type", "camera_cancel").put("id", id).put("reason", reason))
    fun getSettings() = send(JSONObject().put("type", "get_settings"))
    fun setSetting(key: String, value: Any) = send(JSONObject().put("type", "set_setting").put("key", key).put("value", value))
    fun sendText(text: String) = send(JSONObject().put("type", "text").put("text", text))
    fun confirmResult(id: String, ok: Boolean) = send(JSONObject().put("type", "confirm_result").put("id", id).put("ok", ok))
}
