package com.voiceassistant.phone

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the on-device model decided to do with a request (see [OfflineHelper.parse]). */
sealed class OfflineResult {
    data class Say(val text: String) : OfflineResult()
    data class Do(val action: PhoneAction) : OfflineResult()
    object Failed : OfflineResult()
}

/**
 * The fallback for when the PC cannot be reached: the model on the phone (see [OnDeviceModel]) turns a typed request into one small JSON
 * answer, which is either a short reply or an action the phone already knows how to do (alarm, timer, text, call). Actions still wait for the
 * user's yes like any other. Anything that needs the internet or other devices gets an honest "not while the PC is away".
 */
object OfflineHelper {
    fun system(now: LocalDateTime = LocalDateTime.now()): String =
        "You are the offline helper of a voice assistant on a phone; the user's PC is out of reach. Today is " +
            now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)) + ". " +
            "Turn the user's request into exactly one JSON object and nothing else (no code fences). The actions are:\n" +
            "{\"action\":\"set_alarm\",\"time\":\"HH:MM\"} with a 24-hour time, e.g. 7:15 in the evening is \"19:15\".\n" +
            "{\"action\":\"set_timer\",\"seconds\":N} where N is one plain number of seconds, e.g. 10 minutes is 600 and an hour and a half is 5400.\n" +
            "{\"action\":\"send_text\",\"to\":\"NAME\",\"message\":\"TEXT\"} and {\"action\":\"call\",\"to\":\"NAME\"}.\n" +
            "{\"action\":\"answer\",\"text\":\"SHORT ANSWER\"} for maths, dates, conversions, and general knowledge you are sure of; work it out carefully and keep it to one or two sentences.\n" +
            "{\"action\":\"unavailable\",\"reason\":\"SHORT REASON\"} for anything that needs the internet, the PC or other devices (weather, news, lights, music, searching, documents, photos), or that you are not sure about."

    /** Turn the model's reply into something the app can do. Tolerates text around the JSON. */
    fun parse(raw: String, id: String = "offline"): OfflineResult {
        val json = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(raw)?.value ?: return OfflineResult.Failed
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return OfflineResult.Failed
        return when (o.optString("action")) {
            "answer" -> o.optString("text").takeIf { it.isNotBlank() }?.let { OfflineResult.Say(it) } ?: OfflineResult.Failed
            "unavailable" -> OfflineResult.Say("I can't do that while your PC is out of reach." + o.optString("reason").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
            "set_alarm" -> {
                val m = Regex("(\\d{1,2}):(\\d{2})").find(o.optString("time")) ?: return OfflineResult.Failed
                val (h, min) = m.destructured
                if (h.toInt() > 23 || min.toInt() > 59) OfflineResult.Failed
                else OfflineResult.Do(PhoneAction(id, "set_alarm", JSONObject().put("hour", h.toInt()).put("minute", min.toInt())))
            }
            "set_timer" -> {
                val s = o.optString("seconds").toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: return OfflineResult.Failed
                OfflineResult.Do(PhoneAction(id, "set_timer", JSONObject().put("seconds", s)))
            }
            "send_text" -> {
                val to = o.optString("to").takeIf { it.isNotBlank() }; val msg = o.optString("message").takeIf { it.isNotBlank() }
                if (to == null || msg == null) OfflineResult.Failed else OfflineResult.Do(PhoneAction(id, "send_text", JSONObject().put("to", to).put("message", msg)))
            }
            "call" -> o.optString("to").takeIf { it.isNotBlank() }?.let { OfflineResult.Do(PhoneAction(id, "call", JSONObject().put("to", it))) } ?: OfflineResult.Failed
            else -> OfflineResult.Failed
        }
    }
}
