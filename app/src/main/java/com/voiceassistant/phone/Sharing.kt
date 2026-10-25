package com.voiceassistant.phone

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One thing shared to the assistant from another app's share button. */
data class SharedItem(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val kind: String get() = when {
        mime.startsWith("image/") -> "picture"
        mime.startsWith("video/") -> "video"
        mime == "application/pdf" -> "PDF"
        else -> "file"
    }
}

/** What arrived from the share sheet: files, and/or text (often a link). */
data class IncomingShare(val items: List<SharedItem>, val text: String) {
    /** Just a link (perhaps with a page title), not a message that happens to contain one. */
    val isLink: Boolean get() = items.isEmpty() && URL.containsMatchIn(text) && text.replace(URL, "").trim().length < 80 && text.lines().size <= 2

    /** What the assistant is told along with the user's question. */
    fun describe(savedNames: List<String>): String = buildString {
        if (savedNames.isNotEmpty()) append("I shared from my phone: ${savedNames.joinToString(", ")} (now attached). ")
        if (text.isNotBlank()) append(if (isLink) "I shared this link: ${text.trim()} " else "I shared this text: \"${text.trim().take(4000)}\" ")
    }

    /** Ready-made questions that fit what was shared. */
    fun suggestions(): List<String> = when {
        items.any { it.kind == "picture" } -> listOf("What is this?", "Read and translate the text", "Is this a scam?", "Add this to my calendar")
        items.any { it.kind == "video" } -> listOf("What happens in this video?", "Summarize it")
        items.any { it.kind == "PDF" } -> listOf("Summarize this", "What do I need to do?", "Find the dates and amounts")
        isLink -> listOf("Summarize this page", "Is this trustworthy?", "What's the price?")
        else -> listOf("Is this a scam?", "Summarize this", "Help me reply")
    }

    companion object {
        /** What another app shared, or null when the intent is not a share. Only kinds the PC can use are kept. */
        fun from(intent: Intent?, resolver: ContentResolver): IncomingShare? {
            if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return null
            val uris = mutableListOf<Uri>()
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            }
            val items = uris.take(MAX_ITEMS).mapNotNull { uri -> describe(uri, resolver, intent.type) }.filter { usable(it.mime) }
            val text = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "").take(20_000)
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val all = if (subject.isNotBlank() && !text.contains(subject)) "$subject\n$text" else text
            return if (items.isEmpty() && all.isBlank()) null else IncomingShare(items, all.trim())
        }

        /** Files picked inside the app (the attach button). */
        fun fromUris(uris: List<Uri>, resolver: ContentResolver): IncomingShare? {
            val items = uris.take(MAX_ITEMS).mapNotNull { describe(it, resolver, null) }.filter { usable(it.mime) }
            return if (items.isEmpty()) null else IncomingShare(items, "")
        }

        fun usable(mime: String) = mime.startsWith("image/") || mime.startsWith("video/") || mime == "application/pdf" ||
            mime.startsWith("text/") || mime == "application/json"

        private fun describe(uri: Uri, resolver: ContentResolver, fallbackType: String?): SharedItem? = runCatching {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            SharedItem(uri, name, resolver.getType(uri) ?: fallbackType ?: "application/octet-stream", size)
        }.getOrNull()

        const val MAX_ITEMS = 6
        private val URL = Regex("""https?://\S+""")
    }
}

/** Turns a shared item into the bytes sent to the PC: pictures are made upright and shrunk (the PC only needs enough detail to read them). */
object SharePrep {
    const val MAX_SIDE = 2000 // screenshots and documents keep enough detail to read small print
    const val MAX_FILE_BYTES = 300_000_000L // the PC's limit

    /** (name to send, mime to send, the bytes as a stream, total size). */
    fun open(context: Context, item: SharedItem): Prepared {
        val resolver = context.contentResolver
        if (item.mime.startsWith("image/") && Build.VERSION.SDK_INT >= 28) {
            val jpeg = shrink(context, item.uri)
            if (jpeg != null) return Prepared(item.name.substringBeforeLast('.') + ".jpg", "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
        }
        val size = if (item.size > 0) item.size else resolver.openInputStream(item.uri)?.use { s -> s.skip(Long.MAX_VALUE) } ?: 0L
        if (size > MAX_FILE_BYTES) throw IllegalArgumentException("${item.name} is too large to send (the limit is 300 MB).")
        return Prepared(item.name, item.mime, resolver.openInputStream(item.uri) ?: throw IllegalArgumentException("Could not open ${item.name}."), size)
    }

    private fun shrink(context: Context, uri: Uri): ByteArray? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = PhotoPrep.scaledSize(info.size.width, info.size.height, MAX_SIDE)
            decoder.setTargetSize(w, h) // the decoder also turns the picture upright
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    class Prepared(val name: String, val mime: String, val stream: InputStream, val size: Long)
}
