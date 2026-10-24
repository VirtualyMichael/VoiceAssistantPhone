package com.voiceassistant.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone

/** One number someone could be texted or called on. */
data class ContactMatch(val name: String, val number: String, val label: String)

/** Finds who "Mom" or "Alex Smith" is in the phone's own contacts. The names and numbers never leave the phone. */
object Contacts {
    /** Already a phone number, not a name. */
    fun isNumber(text: String): Boolean {
        val t = text.trim()
        return t.count { it.isDigit() } >= 3 && t.all { it.isDigit() || it in "+-() ." }
    }

    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The numbers saved for contacts matching the name, mobile numbers first; at most [limit]. */
    fun find(context: Context, name: String, limit: Int = 6): List<ContactMatch> {
        if (!allowed(context) || name.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(name.trim()))
        val out = mutableListOf<Pair<ContactMatch, Int>>()
        runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 40) {
                    val number = c.getString(1) ?: continue
                    val type = c.getInt(2)
                    val label = Phone.getTypeLabel(context.resources, type, c.getString(3)).toString()
                    out += ContactMatch(c.getString(0) ?: number, number, label) to type
                }
            }
        }
        return rank(out, name).take(limit)
    }

    /** Exact name matches before partial ones, mobile before other numbers, each number once. */
    fun rank(found: List<Pair<ContactMatch, Int>>, name: String): List<ContactMatch> {
        val want = name.trim().lowercase()
        return found
            .sortedWith(compareBy({ it.first.name.lowercase() != want }, { it.second != Phone.TYPE_MOBILE }))
            .map { it.first }
            .distinctBy { it.number.filter { ch -> ch.isDigit() || ch == '+' } }
    }
}
