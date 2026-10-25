package com.voiceassistant.phone

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore

/**
 * Controls whatever is playing on this phone (YouTube Music, Spotify, a video in a browser, a podcast app) through Android's media sessions,
 * which is the same thing the lock-screen player uses. Works with any app that shows media controls, so there is no YouTube Music special case
 * except for starting a search ("play X"), which only that app can do. Android only lets an app see media sessions after the user gave it
 * notification access, so this needs the same switch as reading notifications (see [NotificationReader]).
 */
object MediaControl {
    const val YT_MUSIC = "com.google.android.apps.youtube.music"

    /** Commands the assistant can send; anything else is refused. */
    val COMMANDS = setOf(
        "now_playing", "play", "pause", "toggle", "next", "previous", "stop", "forward", "back", "volume_up", "volume_down", "set_volume", "play_search",
    )

    fun sessions(context: Context): List<MediaController>? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        return try {
            manager.getActiveSessions(ComponentName(context, NotificationReader::class.java))
        } catch (_: SecurityException) {
            null // no notification access yet
        }
    }

    fun appName(context: Context, pkg: String): String =
        runCatching { context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    /** The session to act on: the one the user named, else what is playing, else the most recent one. */
    fun pick(context: Context, all: List<MediaController>, app: String): MediaController? {
        val wanted = app.trim().lowercase()
        if (wanted.isNotEmpty()) {
            all.firstOrNull { appName(context, it.packageName).lowercase().contains(wanted) || it.packageName.lowercase().contains(wanted.replace(" ", "")) }?.let { return it }
            if (wanted in setOf("youtube music", "yt music", "ytmusic")) return all.firstOrNull { it.packageName == YT_MUSIC }
            return null
        }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    fun describe(context: Context, c: MediaController): String {
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val state = when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> "idle"
        }
        val what = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" by ").ifBlank { "nothing selected" }
        return "${appName(context, c.packageName)}: $state, $what"
    }

    /** Returns (worked, text for the assistant). */
    fun perform(context: Context, command: String, app: String, query: String, visible: Boolean): Pair<Boolean, String> {
        if (command !in COMMANDS) return false to "unknown media command '$command'"
        val all = sessions(context) ?: return false to "media_off"
        if (command == "now_playing") {
            return true to if (all.isEmpty()) "Nothing is playing on the phone." else all.joinToString("\n") { "- " + describe(context, it) }
        }
        if (command == "volume_up" || command == "volume_down" || command == "set_volume") return volume(context, command, query)
        val c = pick(context, all, app)
        if (command == "play_search") return playSearch(context, c, app, query, visible)
        if (c == null) return false to (if (app.isBlank()) "No music or video app is active on the phone. Ask the user to start one, or use play_search with a song." else "$app is not running on the phone.")
        val controls = c.transportControls
        when (command) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
            "forward" -> controls.seekTo(((c.playbackState?.position ?: 0L) + 15_000L))
            "back" -> controls.seekTo(((c.playbackState?.position ?: 0L) - 15_000L).coerceAtLeast(0L))
        }
        return true to "Sent '$command' to ${appName(context, c.packageName)}."
    }

    private fun volume(context: Context, command: String, query: String): Pair<Boolean, String> {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = when (command) {
            "volume_up" -> now + maxOf(1, max / 7)
            "volume_down" -> now - maxOf(1, max / 7)
            else -> Math.round((query.trim().removeSuffix("%").toDoubleOrNull() ?: return false to "give the volume as a percentage") / 100.0 * max).toInt()
        }.coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return true to "Media volume is now ${Math.round(target * 100.0 / max)}%."
    }

    /**
     * "Play X": ask an already-running app to search for it (no screen needed); otherwise open YouTube Music with the search, which Android only
     * allows while this app is on screen, so from the background the user taps a notification instead (see [Controller]).
     */
    private fun playSearch(context: Context, running: MediaController?, app: String, query: String, visible: Boolean): Pair<Boolean, String> {
        if (query.isBlank()) return false to "say what to play"
        val c = running ?: pick(context, sessions(context).orEmpty(), "youtube music").takeIf { app.isBlank() || app.lowercase().contains("youtube") }
        if (c != null && ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L) {
            c.transportControls.playFromSearch(query, Bundle())
            return true to "Asked ${appName(context, c.packageName)} to play “$query”."
        }
        val pkg = if (app.isBlank() || app.lowercase().contains("youtube")) YT_MUSIC else null
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (pkg != null) it.setPackage(pkg) }
        if (context.packageManager.resolveActivity(intent, 0) == null) return false to "no music app on the phone can play a search${if (pkg != null) " (YouTube Music is not installed)" else ""}."
        return if (visible) {
            context.startActivity(intent)
            true to "Opened ${if (pkg != null) "YouTube Music" else "the music app"} to play “$query”. If it does not start by itself the user taps play."
        } else {
            Controller.offerMediaIntent(intent, query)
            true to "The phone is locked or another app is open, so Android needs a tap: a notification asks the user to tap to play “$query”."
        }
    }
}
