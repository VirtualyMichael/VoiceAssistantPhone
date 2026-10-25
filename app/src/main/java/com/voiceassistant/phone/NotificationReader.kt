package com.voiceassistant.phone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets the assistant read the notifications on this phone when the user asks ("what did I miss?"). Android only allows it after the user turns on
 * notification access for this app in the phone's settings, and the app also has its own switch (Settings, This phone). Nothing is read until
 * the assistant asks, and only the notifications on screen at that moment are sent to the PC.
 */
class NotificationReader : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    companion object {
        @Volatile private var instance: NotificationReader? = null

        /** The user has given this app notification access in Android's settings. */
        fun accessGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val me = ComponentName(context, NotificationReader::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** The notifications showing now, newest first, as lines for the assistant; null when they cannot be read. */
        fun read(context: Context, app: String, limit: Int, now: Long = System.currentTimeMillis()): String? {
            val service = instance ?: return null
            val list = runCatching { service.activeNotifications }.getOrNull() ?: return null
            val pm = context.packageManager
            val rows = list.asSequence()
                .filter { it.packageName != context.packageName && !it.isOngoing && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    if (app.isNotBlank() && !label.contains(app, ignoreCase = true) && !sbn.packageName.contains(app, ignoreCase = true)) null
                    else describe(sbn, label, now)
                }
                .take(limit.coerceIn(1, 25))
                .toList()
            return if (rows.isEmpty()) (if (app.isBlank()) "There are no notifications on the phone." else "There are no notifications from $app.")
            else rows.joinToString("\n")
        }

        /**
         * Answer a notification that has a reply box (messaging apps do) with the text the user agreed to. The newest one from the app (and, when a
         * name is given, whose title contains it) is used. Returns (done, what to tell the assistant).
         */
        fun reply(context: Context, app: String, who: String, text: String): Pair<Boolean, String> {
            val service = instance ?: return false to "notifications_off"
            val list = runCatching { service.activeNotifications }.getOrNull() ?: return false to "notifications_off"
            val pm = context.packageManager
            for (sbn in list.sortedByDescending { it.postTime }) {
                if (sbn.packageName == context.packageName) continue
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                if (app.isNotBlank() && !label.contains(app, ignoreCase = true) && !sbn.packageName.contains(app, ignoreCase = true)) continue
                val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                if (who.isNotBlank() && !title.contains(who, ignoreCase = true)) continue
                val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: continue
                val inputs = action.remoteInputs
                val fill = android.content.Intent()
                val results = android.os.Bundle().apply { putCharSequence(inputs[0].resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
                return try {
                    action.actionIntent.send(context, 0, fill)
                    true to "Replied to ${title.ifBlank { label }} in $label."
                } catch (e: android.app.PendingIntent.CanceledException) {
                    false to "that notification can no longer be answered"
                }
            }
            return false to "no_reply_target"
        }

        private fun describe(sbn: StatusBarNotification, app: String, now: Long): String? {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            return "- $app, ${ago(now - sbn.postTime)}: ${title.take(80)}${if (text.isNotBlank()) ": " + text.replace('\n', ' ').take(300) else ""}"
        }

        fun ago(ms: Long): String {
            val m = ms / 60_000
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                m < 24 * 60 -> "${m / 60} h ago"
                else -> "${m / (24 * 60)} days ago"
            }
        }
    }
}
