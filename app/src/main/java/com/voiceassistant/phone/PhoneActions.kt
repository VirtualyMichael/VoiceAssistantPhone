package com.voiceassistant.phone

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Something the assistant asked the phone to do (see phone_actions.py on the PC). Nothing happens until the user agrees. */
data class PhoneAction(val id: String, val action: String, val args: JSONObject) {
    /** The question shown to the user. */
    fun describe(): String = when (action) {
        "set_alarm" -> {
            val time = "%02d:%02d".format(args.optInt("hour"), args.optInt("minute"))
            val days = args.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it).replaceFirstChar { c -> c.uppercase() } } }.orEmpty()
            "Set an alarm for $time" + label() + if (days.isNotEmpty()) ", every ${days.joinToString(", ")}?" else "?"
        }
        "set_timer" -> "Start a ${duration(args.optInt("seconds"))} timer" + label() + "?"
        "add_event" -> "Add “${args.optString("title")}” to your calendar on ${pretty(args.optString("start"))}" +
            (args.optString("location").takeIf { it.isNotBlank() }?.let { " at $it" } ?: "") + "?"
        "send_text" -> "Text ${args.optString("to")}: “${args.optString("message")}”"
        "call" -> "Call ${args.optString("to")}?"
        else -> "The assistant asked for something this app does not know how to do yet."
    }

    val known: Boolean get() = action in setOf("set_alarm", "set_timer", "add_event", "send_text", "call", "read_notifications", "media")

    /** Texts and calls go to someone: the user picks the number before agreeing. */
    val needsRecipient: Boolean get() = action == "send_text" || action == "call"

    private fun label() = args.optString("label").takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: ""

    companion object {
        fun duration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
                m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
                s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
            ).joinToString(" ").ifBlank { "0 second" }
        }

        fun pretty(iso: String): String = runCatching {
            LocalDateTime.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm", Locale.getDefault()))
        }.getOrDefault(iso)

        private val DAY_NUMBERS = mapOf(
            "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
        )

        /** Carry out an action the user agreed to; [number] is who they picked for a text or call. Returns (done, what to tell the assistant). */
        fun perform(context: Context, a: PhoneAction, number: String? = null, direct: Boolean = false): Pair<Boolean, String> = runCatching {
            val args = a.args
            when (a.action) {
                "send_text" -> {
                    val to = number ?: return@runCatching false to "no_contact"
                    if (direct && directAllowed(context, "send_text")) {
                        val sms = context.getSystemService(android.telephony.SmsManager::class.java)
                        val parts = sms.divideMessage(args.optString("message"))
                        sms.sendMultipartTextMessage(to, null, parts, null, null)
                        return@runCatching true to "The text to ${args.optString("to")} was sent from the phone."
                    }
                    start(context, Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(to))).putExtra("sms_body", args.optString("message")))
                    true to "The messaging app opened with the text to ${args.optString("to")} filled in; the user taps send there."
                }
                "call" -> {
                    val to = number ?: return@runCatching false to "no_contact"
                    if (direct && directAllowed(context, "call")) {
                        start(context, Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + android.net.Uri.encode(to))))
                        return@runCatching true to "The call to ${args.optString("to")} was started from the phone."
                    }
                    start(context, Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + android.net.Uri.encode(to))))
                    true to "The phone's dialler opened with ${args.optString("to")}'s number; the user taps call there."
                }
                "set_alarm" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, args.optInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, args.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    val days = args.optJSONArray("days")?.let { arr -> (0 until arr.length()).mapNotNull { DAY_NUMBERS[arr.optString(it)] } }.orEmpty()
                    if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                    start(context, intent)
                    true to "The alarm is set on the phone."
                }
                "set_timer" -> {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.optInt("seconds")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    args.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    start(context, intent)
                    true to "The timer is running on the phone."
                }
                "add_event" -> {
                    val zone = ZoneId.systemDefault()
                    val begin = LocalDateTime.parse(args.optString("start")).atZone(zone).toInstant().toEpochMilli()
                    val end = args.optString("end").takeIf { it.isNotBlank() }?.let { LocalDateTime.parse(it).atZone(zone).toInstant().toEpochMilli() } ?: (begin + 3_600_000)
                    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, args.optString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, args.optBoolean("all_day"))
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
                        .putExtra(CalendarContract.Events.DESCRIPTION, args.optString("notes"))
                    start(context, intent)
                    true to "The phone's calendar opened with the event filled in; the user saves it there."
                }
                else -> false to "the phone app does not support '${a.action}' yet"
            }
        }.getOrElse { e -> false to (if (e is android.content.ActivityNotFoundException) "there is no app on the phone that can do that" else "it failed on the phone (${e.message})") }

        /** The permission Android needs to send a text or place a call without another app (asked for when the user turns the setting on). */
        fun directAllowed(context: Context, action: String): Boolean = context.checkSelfPermission(
            if (action == "call") android.Manifest.permission.CALL_PHONE else android.Manifest.permission.SEND_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val DIRECT_PERMISSIONS = arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.CALL_PHONE)

        private fun start(context: Context, intent: Intent) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
