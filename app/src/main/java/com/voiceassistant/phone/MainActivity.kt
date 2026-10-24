package com.voiceassistant.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.voiceassistant.phone.ui.theme.VoiceAssistantPhoneTheme

class MainActivity : FragmentActivity() { // a FragmentActivity so the fingerprint prompt can be shown
    private var micAllowed by mutableStateOf(false)
    private var settingsPage by mutableStateOf<String?>(null) // null: the main screen; "": settings home; otherwise a section id
    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }
    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Controller.turnHandsFree(true) }

    /** Hands-free needs the notification permission on Android 13+ (the listening notice is what keeps it allowed to run). */
    private fun switchHandsFree(on: Boolean) {
        val needsAsk = on && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            !on -> Controller.turnHandsFree(false)
            needsAsk -> askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> Controller.turnHandsFree(true)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        pairFromDebugIntent(intent)
        takeShare(intent)
    }

    /** "Share" from another app opened us: hold on to it until the user asks their question. */
    private fun takeShare(intent: android.content.Intent?) {
        IncomingShare.from(intent, contentResolver)?.let {
            Controller.receiveShare(it)
            settingsPage = null
        }
    }

    override fun onStart() { super.onStart(); Controller.activityStarted() }
    override fun onStop() { super.onStop(); Controller.activityStopped() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Controller.init(this)
        pairFromDebugIntent(intent)
        if (savedInstanceState == null) takeShare(intent)
        micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micAllowed) askForMic.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            VoiceAssistantPhoneTheme {
                BackHandler(enabled = settingsPage != null) { settingsPage = if (settingsPage.isNullOrEmpty()) null else "" }
                DiagnosisDialog()
                Controller.pendingAction?.let { PhoneActionDialog(it) }
                if (Controller.pairing != null && !Controller.setupActive) Controller.incomingShare?.let { ShareSheet(it) }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                        val page = settingsPage
                        when {
                            Controller.pairing == null || Controller.setupActive -> SetupScreen()
                            page != null -> SettingsScreen(
                                page = page,
                                onNavigate = { settingsPage = it },
                                onClose = { settingsPage = null },
                                onHandsFree = ::switchHandsFree,
                                confirmSensitive = { title, proceed ->
                                    BiometricGate.confirm(this@MainActivity, title) { ok, reason ->
                                        if (ok) proceed() else if (reason != null) Controller.showSettingsNote(reason, error = true)
                                    }
                                },
                            )
                            else -> TalkScreen(micAllowed, { askForMic.launch(Manifest.permission.RECORD_AUDIO) }, ::switchHandsFree) { settingsPage = "" }
                        }
                    }
                }
            }
        }
    }
}

/** Developer shortcut: `adb shell am start -n ... --es pairing_b64 <base64 of the pairing code>`. Ignored unless this is a debug build. */
private fun ComponentActivity.pairFromDebugIntent(intent: android.content.Intent?) {
    val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val encoded = intent?.getStringExtra("pairing_b64")
    android.util.Log.i("VoiceAssistant", "debug pairing: debuggable=$debuggable extra=${encoded != null}")
    if (debuggable && intent?.getBooleanExtra("demo_camera", false) == true) { // lets `adb` open the camera screen without the assistant asking
        Controller.openDemoCamera()
        startActivity(android.content.Intent(this, CameraActivity::class.java))
    }
    if (!debuggable || encoded == null) return
    val code = runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)) }.getOrNull()
    android.util.Log.i("VoiceAssistant", "debug pairing accepted=${code?.let { Controller.pairFromText(it) }}")
}

@Composable
private fun TalkScreen(micAllowed: Boolean, askForMic: () -> Unit, switchHandsFree: (Boolean) -> Unit, openSettings: () -> Unit) {
    val listState = rememberLazyListState()
    var typed by remember { mutableStateOf("") }
    val lines = Controller.lines
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Controller.status, style = MaterialTheme.typography.titleMedium)
            TextButton(openSettings) { Text("Settings") }
        }
        if (!Controller.connected) {
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Not connected to your PC", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton({ Controller.runDiagnosis(context) }, enabled = !Controller.diagnosing) { Text(if (Controller.diagnosing) "Checking…" else "Find out why") }
            }
        }
        HandsFreeCard(micAllowed, askForMic, switchHandsFree)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines) { line -> Bubble(line) }
        }
        val live = Controller.partial
        if (live.isNotBlank()) {
            // a quick first guess: the PC re-checks the whole sentence more carefully once you let go
            Text("Hearing: $live", Modifier.fillMaxWidth(), fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (Controller.notice.isNotBlank()) {
            Text(Controller.notice, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
        }
        if (Controller.latency.isNotBlank()) Text(Controller.latency, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!micAllowed) {
                OutlinedButton(askForMic) { Text("Allow the microphone") }
            } else {
                TalkButton()
                Text(Controller.phaseLabel, style = MaterialTheme.typography.titleMedium)
            }
            if (Controller.phase == Phase.Speaking || Controller.phase == Phase.Thinking) {
                OutlinedButton({ Controller.stopSpeaking() }) { Text("Stop") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Or type a message") }, singleLine = true)
            Button({ Controller.sendText(typed); typed = "" }, enabled = Controller.connected) { Text("Send") }
        }
    }
    Controller.approval?.let { ask ->
        AlertDialog(
            onDismissRequest = { Controller.answer(false) },
            title = { Text("Allow this?") },
            text = { Text(ask.prompt) },
            confirmButton = { Button({ Controller.answer(true) }) { Text("Yes") } },
            dismissButton = { TextButton({ Controller.answer(false) }) { Text("No") } },
        )
    }
}

@Composable
private fun TalkButton() {
    val phase = Controller.phase
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        when (phase) {
            Phase.Listening -> colors.primary
            Phase.Armed -> colors.primaryContainer
            Phase.Starting -> colors.primary.copy(alpha = 0.6f)
            Phase.Sending, Phase.Thinking -> colors.tertiary
            Phase.Speaking -> colors.secondary
            else -> colors.secondaryContainer
        },
        label = "fill",
    )
    // the ring grows with how loud you are, so you can see the phone is hearing you
    val ring by animateFloatAsState(if (phase == Phase.Listening) 1f + Controller.level.coerceIn(0f, 1f) * 0.45f else 1f, label = "ring")
    androidx.compose.runtime.LaunchedEffect(phase) {
        if (phase == Phase.Listening && Controller.hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) // the mic is open: go ahead
    }
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(132.dp).scale(ring).clip(CircleShape).background(fill.copy(alpha = 0.25f)))
        Box(
            Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(fill)
                .pointerInput(Controller.connected) {
                    detectTapGestures(onPress = {
                        if (Controller.hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Controller.pressTalk()
                        tryAwaitRelease()
                        Controller.releaseTalk()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (phase) {
                    Phase.Listening -> "Listening"
                    Phase.Starting -> "…"
                    Phase.Sending, Phase.Thinking -> "Thinking"
                    Phase.Armed -> "Ready"
                    Phase.Speaking -> "Speaking"
                    else -> "Talk"
                },
                color = if (phase == Phase.Idle || phase == Phase.Offline) colors.onSecondaryContainer
                else if (phase == Phase.Armed) colors.onPrimaryContainer else colors.onPrimary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun Bubble(line: Line) {
    if (line.text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text,
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (line.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HandsFreeCard(micAllowed: Boolean, askForMic: () -> Unit, switchHandsFree: (Boolean) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var phrase by remember(Controller.wakePhrase) { mutableStateOf(Controller.wakePhrase) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Hands-free", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (Controller.handsFree) "Say “${Controller.wakePhrase}”, then ask. Works with the screen off." else "Talk without touching the phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.material3.Switch(
                checked = Controller.handsFree,
                onCheckedChange = { if (it && !micAllowed) askForMic() else switchHandsFree(it) },
                enabled = Controller.connected || Controller.handsFree,
            )
        }
        if (Controller.hfNotice.isNotBlank()) Text(Controller.hfNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton({ editing = !editing }) { Text(if (editing) "Hide wake phrase settings" else "Wake phrase settings") }
        if (editing) {
            OutlinedTextField(
                phrase, { phrase = it }, Modifier.fillMaxWidth(), label = { Text("Wake phrase") }, singleLine = true,
                supportingText = { Text("Plain words work best, like “Hey Jarvis”. Two or three syllables or more is more reliable.") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", style = MaterialTheme.typography.bodyMedium)
                listOf("Low", "Normal", "High").forEachIndexed { i, name ->
                    if (Controller.sensitivity == i) Button({ Controller.changeSensitivity(i) }) { Text(name) }
                    else OutlinedButton({ Controller.changeSensitivity(i) }) { Text(name) }
                }
            }
            Text("High hears you from further away but may start by mistake. Low is stricter.", style = MaterialTheme.typography.bodySmall)
            Button({ Controller.changeWakePhrase(phrase) }, enabled = phrase.isNotBlank() && phrase.trim() != Controller.wakePhrase) { Text("Save wake phrase") }
        }
    }
}

/** The answer to "Find out why" / "Test the connection": the most likely cause, with a button for the fix. */
@Composable
private fun DiagnosisDialog() {
    val d = Controller.diagnosis ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { Controller.clearDiagnosis() },
        title = { Text(if (d.ok) "All good" else "Here's the problem") },
        text = { Text(d.message) },
        confirmButton = {
            when (d.fix) {
                Fix.InstallTailscale -> Button({ Controller.clearDiagnosis(); Environment.installTailscale(context) }) { Text("Get Tailscale") }
                Fix.OpenTailscale -> Button({ Controller.clearDiagnosis(); Environment.openTailscale(context) }) { Text("Open Tailscale") }
                Fix.PairAgain -> Button({ Controller.clearDiagnosis(); Controller.startSetup() }) { Text("Pair again") }
                else -> Button({ Controller.clearDiagnosis() }) { Text("OK") }
            }
        },
        dismissButton = if (d.fix in listOf(Fix.InstallTailscale, Fix.OpenTailscale, Fix.PairAgain)) {
            { TextButton({ Controller.clearDiagnosis() }) { Text("Not now") } }
        } else null,
    )
}
