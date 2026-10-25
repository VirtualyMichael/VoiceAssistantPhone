package com.voiceassistant.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

/** What the assistant was asked to remember ("remember that my dog is Rex"), kept on the PC. Tap one to change it, clear its text to delete it. */
@Composable
fun MemoryScreen(onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) } // a position in the list; -1 is a new note
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(Controller.connected) { Controller.loadNotes() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("‹ Back") }
            Text("What it remembers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton({ draft = ""; editing = -1 }, enabled = Controller.connected) { Text("Add") }
        }
        when {
            !Controller.connected -> Text("Connect to your PC to see what it remembers; the notes are kept there.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
            Controller.notes.isEmpty() -> Text(if (Controller.notesLoading) "Loading…" else "Nothing yet. Say “remember that…” or tap Add.", modifier = Modifier.padding(8.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(Controller.notes) { position, note ->
                    Column(Modifier.fillMaxWidth().clickable { draft = note.text; editing = position }.padding(vertical = 10.dp, horizontal = 4.dp)) {
                        Text(note.text, style = MaterialTheme.typography.bodyLarge)
                        if (note.at.isNotBlank()) Text(note.at, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    editing?.let { position ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (position < 0) "Remember this" else "Change this note") },
            text = {
                Column {
                    OutlinedTextField(draft, { draft = it.take(1000) }, Modifier.fillMaxWidth(), minLines = 2)
                    if (position >= 0) Text("Clear the text and save to delete it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button({
                    if (position < 0) Controller.addNote(draft) else Controller.editNote(position, draft)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
}
