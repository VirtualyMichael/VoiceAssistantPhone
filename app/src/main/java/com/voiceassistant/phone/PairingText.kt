package com.voiceassistant.phone

import org.json.JSONObject

/** What a scanned, pasted or typed pairing text turned out to be. */
sealed interface PairingInput {
    /** The old format: the PC's permanent token for this phone is inside. */
    data class Token(val pairing: Pairing) : PairingInput

    /** The one-time code the PC shows (valid once, for five minutes). The phone swaps it for its own token when it connects. */
    data class Code(val host: String, val port: Int, val code: String, val name: String) : PairingInput

    data object Invalid : PairingInput
}

object PairingText {
    const val DEFAULT_PORT = 47830
    private const val CODE_LENGTH = 10
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val typed = Regex("""^\s*([A-Za-z0-9.\-]+)(?::(\d{2,5}))?\s+([A-Za-z0-9\- ]{8,16})\s*$""")

    /** Accepts the QR text (JSON), the same text pasted, or `100.1.2.3:47830 ABCD-EFGH-JK` typed by hand. */
    fun parse(text: String): PairingInput {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return parseJson(trimmed)
        val m = typed.matchEntire(trimmed) ?: return PairingInput.Invalid
        val code = normalize(m.groupValues[3])
        val port = m.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }.toIntOrNull() ?: return PairingInput.Invalid
        if (!validCode(code) || port !in 1024..65535) return PairingInput.Invalid
        return PairingInput.Code(m.groupValues[1], port, code, "My PC")
    }

    private fun parseJson(text: String): PairingInput = try {
        val o = JSONObject(text)
        val host = o.getString("host")
        val port = o.getInt("port")
        val name = o.optString("name", "").ifBlank { "My PC" }
        when {
            o.has("code") -> normalize(o.getString("code")).let { if (validCode(it) && port in 1024..65535 && host.isNotBlank()) PairingInput.Code(host, port, it, name) else PairingInput.Invalid }
            o.has("token") -> Pairing.parse(text)?.let { PairingInput.Token(it) } ?: PairingInput.Invalid
            else -> PairingInput.Invalid
        }
    } catch (e: Exception) {
        PairingInput.Invalid
    }

    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    private fun validCode(code: String) = code.length == CODE_LENGTH && code.all { it in ALPHABET }

    /** ABCDEFGHJK -> ABCD-EFGH-JK, for showing to a person. */
    fun format(code: String): String = listOf(code.take(4), code.drop(4).take(4), code.drop(8)).filter { it.isNotEmpty() }.joinToString("-")
}
