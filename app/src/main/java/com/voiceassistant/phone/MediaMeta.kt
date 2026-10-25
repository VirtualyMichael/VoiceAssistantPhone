package com.voiceassistant.phone

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone knows about a picture or video it is about to send: when it was taken, with what, how big, how long and (when the file still
 * has it) where. It goes to the PC next to the file, so "when was this taken?" and "where is this?" can be answered. Nothing here is read from
 * the picture's pixels; it is the file's own header. [places] leaves the position out.
 */
object MediaMeta {
    fun describe(context: Context, item: SharedItem, places: Boolean): String = runCatching {
        val parts = mutableListOf<String>()
        when {
            item.mime.startsWith("image/") -> context.contentResolver.openInputStream(item.uri)?.use { s ->
                val exif = ExifInterface(s)
                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let { parts += "taken ${prettyExifDate(it)}" }
                val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").trim()
                if (camera.isNotBlank()) parts += "camera: $camera"
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) parts += "original size ${w}x$h"
                if (places) exif.latLong?.let { parts += "place: ${"%.5f".format(Locale.US, it[0])}, ${"%.5f".format(Locale.US, it[1])} (GPS)" }
            }
            item.mime.startsWith("video/") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, item.uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { parts += "recorded ${prettyVideoDate(it)}" }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { parts += "length ${PhoneAction.duration((it / 1000).toInt())}" }
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) parts += "${w}x$h"
                    if (places) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { parts += "place: ${it.trim('/')} (GPS)" }
                } finally {
                    r.release()
                }
            }
        }
        if (item.size > 0) parts += "file size ${sizeText(item.size)}"
        parts.joinToString("; ")
    }.getOrDefault("")

    private fun prettyExifDate(raw: String): String = runCatching {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.let { SimpleDateFormat("EEE d MMM yyyy 'at' HH:mm", Locale.getDefault()).format(it) }
    }.getOrNull() ?: raw

    private fun prettyVideoDate(raw: String): String = runCatching {
        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(raw.substringBefore('.'))?.let { SimpleDateFormat("EEE d MMM yyyy 'at' HH:mm", Locale.getDefault()).format(it) }
    }.getOrNull() ?: raw

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
        bytes >= 1000 -> "${bytes / 1000} KB"
        else -> "$bytes bytes"
    }
}

/** A small picture to show in the chat for something that was sent: the picture itself, or a frame of the video; null for other files. */
object Thumbnails {
    fun load(context: Context, item: SharedItem, edge: Int = 480): android.graphics.Bitmap? = runCatching {
        when {
            item.mime.startsWith("image/") -> {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, item.uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val (w, h) = PhotoPrep.scaledSize(info.size.width, info.size.height, edge)
                    decoder.setTargetSize(w, h)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            item.mime.startsWith("video/") -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, item.uri)
                    r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    r.release()
                }
            }
            else -> null
        }
    }.getOrNull()
}
