package com.voiceassistant.phone

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The conversations saved on the PC, newest first. Tap one to continue it (here and on the PC); delete removes it from the PC after asking. */
@Composable
fun HistoryScreen(onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    LaunchedEffect(Controller.connected) { Controller.loadChats() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("‹ Back") }
            Text("Chat history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        when {
            !Controller.connected -> Text("Connect to your PC to see your chats; they are saved there.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            Controller.chats.isEmpty() -> Text(if (Controller.chatsLoading) "Loading…" else "No saved chats yet. Chats are saved on your PC as you talk.", modifier = Modifier.padding(8.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(Controller.chats, key = { it.id }) { chat ->
                    Row(Modifier.fillMaxWidth().clickable { Controller.openChat(chat.id); onClose() }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(chat.title.ifBlank { "Chat" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val ago = DateUtils.getRelativeTimeSpanString(chat.updated * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                            Text("$ago  ·  ${chat.count} messages" + if (chat.id == Controller.currentChatId) "  ·  current" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ deleting = chat }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${chat.title.take(80)}” will be removed from your PC. This cannot be undone.") },
            confirmButton = { TextButton({ Controller.deleteChat(chat.id); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}
