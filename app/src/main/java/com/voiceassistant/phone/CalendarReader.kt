package com.voiceassistant.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reads the events on this phone's calendar for the next few days, only when the assistant asks and only while the user has switched it on in
 * Settings and allowed Android's calendar permission. Nothing is stored; the lines go to the PC for that one answer.
 */
object CalendarReader {
    val PERMISSION = Manifest.permission.READ_CALENDAR

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Events from the start of today for [days] days (1 = today only), one line each, in time order. */
    fun read(context: Context, days: Int, now: Long = System.currentTimeMillis()): String {
        val start = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days.coerceIn(1, 14)) }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = mutableListOf<Pair<Long, String>>()
        val cursor = CalendarContract.Instances.query(context.contentResolver, projection, start.timeInMillis, end.timeInMillis)
        cursor?.use {
            while (it.moveToNext() && lines.size < 40) {
                val title = it.getString(0).orEmpty().ifBlank { "(no title)" }.take(100)
                val begin = it.getLong(1)
                val finish = it.getLong(2)
                val allDay = it.getInt(3) == 1
                val where = it.getString(4).orEmpty().takeIf { s -> s.isNotBlank() }?.let { s -> " at ${s.take(60)}" }.orEmpty()
                val when_ = if (allDay) "all day" else "${clock.format(Date(begin))}-${clock.format(Date(finish))}"
                lines += begin to "- ${day.format(Date(begin))}, $when_: $title$where"
            }
        }
        return if (lines.isEmpty()) "Nothing on the calendar for ${if (days <= 1) "today" else "the next $days days"}."
        else lines.sortedBy { it.first }.joinToString("\n") { it.second }
    }
}
