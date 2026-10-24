package com.voiceassistant.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Where the settings screens are: "" is the home page, anything else is a section id. */
@Composable
fun SettingsScreen(
    page: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    onHandsFree: (Boolean) -> Unit,
    confirmSensitive: (title: String, proceed: () -> Unit) -> Unit,
) {
    LaunchedEffect(Controller.connected) { Controller.loadSettings() }
    val snapshot = Controller.settings
    val phoneItems = Controller.phoneItems()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SettingItem?>(null) }

    fun send(item: SettingItem, value: Any) {
        when {
            item.key == "phone.handsfree" -> onHandsFree(value == true)
            item.key.startsWith("phone.") -> Controller.changePhoneSetting(item.key, value)
            item.sensitive -> confirmSensitive("Change “${item.label}”?") { Controller.changeSetting(item.key, value) }
            else -> Controller.changeSetting(item.key, value)
        }
    }

    fun open(item: SettingItem) {
        when {
            item.kind == "readonly" -> Controller.showSettingsNote("${item.label} is edited on the PC.")
            item.locked -> Controller.showSettingsNote("Locked. Turn on “Let my phone change safety settings” on the PC (General page) to change this.", error = true)
            else -> editing = item
        }
    }

    val section = snapshot?.section(page)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (page.isEmpty()) onClose() else onNavigate("") }) { Text(if (page.isEmpty()) "‹ Close" else "‹ Settings") }
                Text(section?.title ?: "Settings", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (page.isEmpty()) {
                    item {
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
                            placeholder = { Text("Search settings") },
                        )
                    }
                    if (query.isNotBlank()) {
                        val results = phoneItems.filter { "${it.label} ${it.help}".contains(query.trim(), ignoreCase = true) } + snapshot?.search(query).orEmpty()
                        if (results.isEmpty()) item { Text("Nothing matches “$query”.", Modifier.padding(16.dp)) }
                        items(results, key = { it.key }) { rowFor(it, ::send, ::open) }
                    } else {
                        if (snapshot == null) {
                            item {
                                Text(
                                    if (Controller.connected) "Loading the settings from your PC…" else "Connect to your PC to see its settings. The settings for this phone work without it.",
                                    Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        if (snapshot?.restartPending == true) item { Banner("Some changes apply when the assistant restarts.") }
                        if (snapshot != null && !snapshot.allowSafety) {
                            item { Banner("Safety settings are locked. To change them from your phone, turn on “Let my phone change safety settings” on the PC (General page).") }
                        }
                        val essentials = snapshot?.essentials().orEmpty()
                        if (essentials.isNotEmpty()) {
                            item { GroupTitle("Essentials") }
                            items(essentials, key = { "e." + it.key }) { rowFor(it, ::send, ::open) }
                        }
                        item { GroupTitle("This phone") }
                        items(phoneItems, key = { it.key }) { rowFor(it, ::send, ::open) }
                        if (snapshot != null) {
                            item { GroupTitle("All settings on the PC") }
                            items(snapshot.sections, key = { "s." + it.id }) { s ->
                                Column(Modifier.fillMaxWidth().clickable { onNavigate(s.id) }.padding(vertical = 12.dp, horizontal = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                            Text("${s.blurb}  (${s.items.size})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("›", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        item { Box(Modifier.heightIn(min = 72.dp)) }
                    }
                } else if (section != null) {
                    item { Text(section.blurb, Modifier.padding(horizontal = 4.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(section.items, key = { it.key }) { rowFor(it, ::send, ::open) }
                    item { Box(Modifier.heightIn(min = 72.dp)) }
                } else {
                    item { Text("This section is not available right now.", Modifier.padding(16.dp)) }
                }
            }
        }
        Controller.settingsNote?.let { NoteBar(it, Modifier.align(Alignment.BottomCenter)) }
    }
    editing?.let { item ->
        EditorSheet(item, onDismiss = { editing = null }, onSave = { value -> send(item, value); editing = null })
    }
}

@Composable
private fun rowFor(item: SettingItem, send: (SettingItem, Any) -> Unit, open: (SettingItem) -> Unit) {
    SettingRow(item, onToggle = { send(item, it) }, onTap = { open(item) })
    HorizontalDivider()
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun SettingRow(item: SettingItem, onToggle: (Boolean) -> Unit, onTap: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clickable { if (item.kind == "toggle" && !item.locked) onToggle(item.value != true) else onTap() }
            .alpha(if (item.locked) 0.55f else 1f)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                if (item.kind != "toggle") Text(item.valueText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.restartNeeded && !item.locked && item.kind != "readonly") {
                    Text("Applies after a restart", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton({ showHelp = !showHelp }) { Text("?") }
            when {
                item.locked -> Text("🔒")
                item.kind == "toggle" -> Switch(checked = item.value == true, onCheckedChange = { onToggle(it) })
                else -> Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (showHelp) {
            Text(item.help, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
            if (item.kind == "readonly") item.items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (item.sensitive) Text("A safety setting: your phone asks for your fingerprint or PIN first.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NoteBar(note: SettingsNote, modifier: Modifier) {
    LaunchedEffect(note) {
        delay(6000)
        Controller.clearSettingsNote(note)
    }
    Surface(
        modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (note.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
        contentColor = if (note.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(note.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (note.undoKey != null && note.undoValue != null) TextButton({ Controller.undoSetting() }) { Text("Undo") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(item: SettingItem, onDismiss: () -> Unit, onSave: (Any) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = item.kind != "choice")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleLarge)
            Text(item.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (item.kind) {
                "choice" -> ChoiceEditor(item, onSave)
                "number" -> NumberEditor(item, onSave)
                else -> TextEditor(item, onSave)
            }
            Box(Modifier.heightIn(min = 16.dp))
        }
    }
}

@Composable
private fun ChoiceEditor(item: SettingItem, onSave: (Any) -> Unit) {
    var other by remember { mutableStateOf("") }
    Column {
        item.choices.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onSave(choice.value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice.value == item.value, onClick = { onSave(choice.value) })
                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (item.allowOther) {
            OutlinedTextField(other, { other = it.take(item.maxLength) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Or type another") })
            Button({ onSave(other.trim()) }, Modifier.padding(top = 8.dp), enabled = other.isNotBlank()) { Text("Use this") }
        }
    }
}

@Composable
private fun NumberEditor(item: SettingItem, onSave: (Any) -> Unit) {
    var v by remember { mutableFloatStateOf(((item.value as? Double) ?: item.min).toFloat()) }
    val count = (((item.max - item.min) / item.step).roundToInt() - 1).coerceAtLeast(0)
    val steps = if (count > 100) 0 else count // thousands of tick marks would make the slider slow, so wide ranges slide freely
    fun settled(): Double {
        val raw = v.toDouble()
        val snapped = if (item.integer) raw.roundToInt().toDouble() else ((raw - item.min) / item.step).roundToInt() * item.step + item.min
        return (snapped * 10000).roundToInt() / 10000.0
    }
    Text(item.formatNumber(settled()), style = MaterialTheme.typography.headlineMedium)
    Slider(value = v, onValueChange = { v = it }, valueRange = item.min.toFloat()..item.max.toFloat(), steps = steps)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (item.default as? Double)?.let { default ->
            OutlinedButton({ v = default.toFloat() }, enabled = settled() != default) { Text("Reset to ${item.formatNumber(default)}") }
        }
        Button({ onSave(settled()) }, enabled = settled() != item.value) { Text("Save") }
    }
}

@Composable
private fun TextEditor(item: SettingItem, onSave: (Any) -> Unit) {
    var t by remember { mutableStateOf((item.value as? String).orEmpty()) }
    OutlinedTextField(
        t, { t = it.take(item.maxLength) }, Modifier.fillMaxWidth(), singleLine = !item.multiline, minLines = if (item.multiline) 4 else 1,
        supportingText = { Text("${t.length} / ${item.maxLength}") },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (item.default as? String)?.let { default ->
            OutlinedButton({ t = default }, enabled = t != default) { Text("Reset") }
        }
        Button({ onSave(t) }, enabled = t != item.value && (t.isNotBlank() || item.key != "phone.wake_phrase")) { Text("Save") }
    }
}
