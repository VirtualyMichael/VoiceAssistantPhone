package com.voiceassistant.phone

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer release of this app on GitHub. It only asks which version is newest (one small request, nothing about you is sent) and
 * then offers the release page; the app never downloads or installs anything by itself.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/VirtualyMichael/VoiceAssistantPhone/releases/latest"

    data class Release(val version: String, val url: String, val notes: String)

    /** "v0.16.1" or "0.16.1-draft" to [0, 16, 1]; anything that is not numbers is ignored. */
    fun parts(version: String): List<Int> = version.trim().removePrefix("v").substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }

    fun newer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parse(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name")
        val url = o.optString("html_url")
        if (tag.isBlank() || !url.startsWith("https://github.com/")) return null // only ever offer a GitHub page
        Release(tag.removePrefix("v"), url, o.optString("body").take(600))
    }.getOrNull()

    /** Blocking: call from a worker thread. Null when offline or GitHub did not answer. */
    fun fetchLatest(): Release? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "VoiceAssistantPhone")
        if (c.responseCode != 200) return null
        parse(c.inputStream.bufferedReader().readText())
    }.getOrNull()
}
