package com.voiceassistant.phone

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class CardSource(val title: String, val url: String, val site: String)
data class CardSetting(val section: String, val label: String)
class CardImage(val jpeg: ByteArray, val page: String, val caption: String, val site: String)

/** What an answer was based on, sent by the PC after the reply (see answer_cards.py there): pages, pictures and settings to tap. */
data class AnswerCard(val id: String, val sources: List<CardSource>, val settings: List<CardSetting>, val images: List<CardImage>) {
    val isEmpty get() = sources.isEmpty() && settings.isEmpty() && images.isEmpty()

    companion object {
        fun parse(o: JSONObject): AnswerCard {
            fun <T> list(name: String, make: (JSONObject) -> T?): List<T> =
                o.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(make) } }.orEmpty()
            return AnswerCard(
                o.optString("id"),
                list("sources") { s -> s.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { CardSource(s.optString("title"), it, s.optString("site")) } },
                list("settings") { s -> CardSetting(s.optString("section"), s.optString("label")) },
                list("images") { s ->
                    val jpeg = runCatching { android.util.Base64.decode(s.optString("jpeg_b64"), android.util.Base64.DEFAULT) }.getOrNull()
                    if (jpeg == null || jpeg.isEmpty()) null else CardImage(jpeg, s.optString("page"), s.optString("caption"), s.optString("site"))
                },
            )
        }

        private val URL = Regex("""https?://[^\s<>"')\]]+""")

        /** The reply with its web addresses made tappable. */
        fun linkify(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
            var at = 0
            for (m in URL.findAll(text)) {
                val url = m.value.trimEnd('.', ',', ';', ':')
                append(text.substring(at, m.range.first))
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))))
                append(url)
                pop()
                at = m.range.first + url.length
            }
            append(text.substring(at))
        }
    }
}

private const val SHOWN_SOURCES = 3

/** The card under a reply: tap a source or picture to open its page, tap a setting to open it in Settings. */
@Composable
fun AnswerCardView(card: AnswerCard, openSettings: (String) -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(card.images, key = { it.page + it.caption }) { img ->
                    val bitmap = remember(img) { BitmapFactory.decodeByteArray(img.jpeg, 0, img.jpeg.size)?.asImageBitmap() }
                    Column(Modifier.width(140.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = img.page.startsWith("http")) { runCatching { uri.openUri(img.page) } }) {
                        if (bitmap != null) Image(bitmap, img.caption, Modifier.size(140.dp, 105.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        Text(img.site.ifBlank { img.caption }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp))
                    }
                }
            }
        }
        if (card.sources.isNotEmpty()) {
            var all by rememberSaveable(card.id) { mutableStateOf(false) }
            val shown = if (all) card.sources else card.sources.take(SHOWN_SOURCES)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("Sources", Modifier.padding(start = 12.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (card.sources.size > SHOWN_SOURCES) {
                        TextButton({ all = !all }, Modifier.padding(start = 4.dp)) {
                            Text(if (all) "Show fewer" else "Show ${card.sources.size - SHOWN_SOURCES} more", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    shown.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s.url) } }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🔗", Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (card.settings.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.settings.forEach { s -> AssistChip(onClick = { openSettings(s.section) }, label = { Text("⚙️ ${s.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
        }
    }
}
