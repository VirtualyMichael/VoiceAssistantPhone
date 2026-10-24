package com.voiceassistant.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Something shared from another app: what it is, a few ready-made questions, and a box for your own. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(share: IncomingShare) {
    val context = LocalContext.current
    var question by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { Controller.dismissShare() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ask the assistant about this", style = MaterialTheme.typography.titleLarge)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    share.items.forEach { item ->
                        val icon = when (item.kind) { "picture" -> "🖼️"; "video" -> "🎬"; "PDF" -> "📄"; else -> "📎" }
                        val size = if (item.size > 0) "  ·  " + sizeText(item.size) else ""
                        Text("$icon  ${item.name}$size", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    if (share.text.isNotBlank()) Text((if (share.isLink) "🔗  " else "📝  ") + share.text.take(300), style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                share.suggestions().forEach { s -> AssistChip(onClick = { question = s }, label = { Text(s) }) }
            }
            OutlinedTextField(
                question, { question = it.take(1000) }, Modifier.fillMaxWidth(), placeholder = { Text("Your question (optional)") }, minLines = 2,
            )
            if (Controller.shareStatus.isNotBlank()) {
                Text(Controller.shareStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (Controller.sharing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            if (!Controller.connected) Text("Not connected to your PC. It will need to be on and reachable.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button({ Controller.sendShare(context, question) }, Modifier.fillMaxWidth(), enabled = Controller.connected && !Controller.sharing) {
                Text(if (Controller.sharing) "Sending…" else "Ask")
            }
            TextButton({ Controller.dismissShare() }, Modifier.fillMaxWidth(), enabled = !Controller.sharing) { Text("Cancel") }
        }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1000 -> "${bytes / 1000} KB"
    else -> "$bytes bytes"
}

/** The assistant wants to do something on this phone; nothing happens unless the user says yes. */
@Composable
fun PhoneActionDialog(action: PhoneAction) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { Controller.answerAction(context, false) },
        title = { Text("Your assistant asks") },
        text = { Text(action.describe()) },
        confirmButton = { Button({ Controller.answerAction(context, true) }) { Text(if (action.action == "add_event") "Open calendar" else "Yes") } },
        dismissButton = { TextButton({ Controller.answerAction(context, false) }) { Text("No") } },
    )
}
