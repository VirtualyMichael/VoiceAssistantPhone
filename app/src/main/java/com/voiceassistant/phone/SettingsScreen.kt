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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A picture for each section the PC names (remote_settings.SECTION_ICONS); emoji, so no icon library is needed. */
private val SECTION_ICONS = mapOf(
    "mic" to "🎙️", "speaker" to "🗣️", "phone" to "📱", "camera" to "📷", "brain" to "🧠", "memory" to "💾", "music" to "🎵",
    "bulb" to "💡", "shield" to "🛡️", "code" to "💻", "headset" to "🥽", "sound" to "🎧",
)

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
    var query by rememberSaveable { mutableStateOf("") }
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

    val row: @Composable (SettingItem, Boolean) -> Unit = { item, showWhere -> SettingRow(item, showWhere, { send(item, it) }, { open(item) }) }
    val section = snapshot?.section(page)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ if (page.isEmpty()) onClose() else onNavigate("") }) { Text(if (page.isEmpty()) "‹ Close" else "‹ Settings") }
                section?.let { Text(SECTION_ICONS[it.icon] ?: "⚙️", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleLarge) }
                Text(section?.title ?: "Settings", style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page.isEmpty()) {
                    item {
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true, shape = RoundedCornerShape(28.dp),
                            placeholder = { Text("Search, or ask: “make it talk slower”") },
                            leadingIcon = { Text("🔍") },
                            trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("✕") } },
                        )
                    }
                    if (Controller.settingsQuestion.isNotEmpty()) item { AnswerCard() }
                    if (query.isNotBlank()) {
                        val results = SettingsSearch.search(phoneItems, query) + snapshot?.search(query).orEmpty()
                        item { AskCard(query, nothingFound = results.isEmpty()) }
                        if (results.isNotEmpty()) item { Group(null) { results.forEachIndexed { i, it -> if (i > 0) HorizontalDivider(); row(it, true) } } }
                    } else {
                        if (snapshot == null) {
                            item {
                                Text(
                                    if (Controller.connected) "Loading the settings from your PC…" else "Connect to your PC to see its settings. The settings for this phone work without it.",
                                    Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item { Group("Your PC") { PcGroup(onClose) } }
                        if (snapshot?.restartPending == true) item { Banner("Some changes apply when the assistant restarts.") }
                        if (snapshot != null && !snapshot.allowSafety) {
                            item { Banner("🔒 Safety settings are locked. To change them from your phone, turn on “Let my phone change safety settings” on the PC (General page).") }
                        }
                        val essentials = snapshot?.essentials().orEmpty()
                        if (essentials.isNotEmpty()) {
                            item { Group("Most used") { essentials.forEachIndexed { i, it -> if (i > 0) HorizontalDivider(); row(it, false) } } }
                        }
                        item { Group("This phone") { phoneItems.forEachIndexed { i, it -> if (i > 0) HorizontalDivider(); row(it, false) } } }
                        if (snapshot != null) {
                            item {
                                Group("Everything on the PC") {
                                    snapshot.sections.forEachIndexed { i, s ->
                                        if (i > 0) HorizontalDivider()
                                        SectionRow(s) { onNavigate(s.id) }
                                    }
                                }
                            }
                        }
                        item { Box(Modifier.heightIn(min = 72.dp)) }
                    }
                } else if (section != null) {
                    item { Text(section.blurb, Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val everyday = section.everyday
                    if (everyday.isNotEmpty()) item { Group(null) { everyday.forEachIndexed { i, it -> if (i > 0) HorizontalDivider(); row(it, false) } } }
                    val advanced = section.advanced
                    if (advanced.isNotEmpty()) item { AdvancedGroup(advanced, startOpen = everyday.isEmpty()) { row(it, false) } }
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

/** A rounded card holding a group of rows, with an optional title above it. */
@Composable
private fun Group(title: String?, content: @Composable () -> Unit) {
    Column {
        if (title != null) Text(title, Modifier.padding(start = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun AdvancedGroup(items: List<SettingItem>, startOpen: Boolean, row: @Composable (SettingItem) -> Unit) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column {
        TextButton({ open = !open }) { Text((if (open) "▾  " else "▸  ") + "Advanced settings (${items.size})") }
        if (open) Group(null) { items.forEachIndexed { i, it -> if (i > 0) HorizontalDivider(); row(it) } }
        else Text("Most people never need these.", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionRow(s: SettingSection, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(SECTION_ICONS[s.icon] ?: "⚙️")
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge)
            Text(s.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Text("›", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Offers the search words to the assistant as a question: it knows every setting, explains it, and can change the safe ones. */
@Composable
private fun AskCard(query: String, nothingFound: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (nothingFound) "No setting matches. Ask the assistant?" else "Not sure which one?", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (Controller.connected) "It explains settings, says where they are and can change the safe ones." else "Connect to your PC to ask the assistant.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            FilledTonalButton({ Controller.askAboutSettings(query) }, enabled = Controller.connected) { Text("Ask") }
        }
    }
}

@Composable
private fun AnswerCard() {
    val answer = Controller.settingsAnswer
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You asked: “${Controller.settingsQuestion}”", style = MaterialTheme.typography.labelLarge)
            Text(answer.ifBlank { "Thinking…" }, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ Controller.clearSettingsQuestion() }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun SettingRow(item: SettingItem, showWhere: Boolean, onToggle: (Boolean) -> Unit, onTap: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clickable { if (item.kind == "toggle" && !item.locked) onToggle(item.value != true) else onTap() }
            .alpha(if (item.locked) 0.55f else 1f)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (showWhere && item.sectionTitle.isNotBlank()) {
                    Text(item.sectionTitle + if (item.advanced) " › Advanced" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
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
            if (Controller.connected && !item.key.startsWith("phone.")) {
                TextButton({ Controller.askAboutSettings("What does “${item.label}” do, and what would you suggest for me?") }) { Text("Ask the assistant about this") }
            }
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

@Composable
private fun PcGroup(onClose: () -> Unit) {
    val context = LocalContext.current
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Controller.pairing?.let { "${it.name}  ·  ${it.host}" } ?: "Not paired", style = MaterialTheme.typography.bodyLarge)
        Text(Controller.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ Controller.runDiagnosis(context) }, enabled = !Controller.diagnosing) { Text(if (Controller.diagnosing) "Checking…" else "Test the connection") }
            OutlinedButton({ Controller.startSetup(); onClose() }) { Text("Run setup again") }
        }
        TextButton({ confirmForget = true }) { Text("Forget this PC", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this PC?") },
            text = { Text("Your PC will take this phone off its list, so you'll need to pair again to use it.") },
            confirmButton = { Button({ confirmForget = false; Controller.forget(); onClose() }) { Text("Forget") } },
            dismissButton = { TextButton({ confirmForget = false }) { Text("Cancel") } },
        )
    }
}
