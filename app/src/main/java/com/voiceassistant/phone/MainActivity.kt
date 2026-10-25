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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.voiceassistant.phone.ui.theme.VoiceAssistantPhoneTheme

class MainActivity : FragmentActivity() { // a FragmentActivity so the fingerprint prompt can be shown
    private var micAllowed by mutableStateOf(false)
    private var historyOpen by mutableStateOf(false) // the saved chats from the PC
    private var settingsPage by mutableStateOf<String?>(null) // null: the main screen; "": settings home; otherwise a section id
    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { micAllowed = it }
    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Controller.turnHandsFree(true) }
    private var contactsAllowed by mutableStateOf(false) // texts and calls look names up in the phone's contacts
    private val askForContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { contactsAllowed = it }
    private val askForDirect = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        Controller.directPermissionAsk = false
        if (!granted.values.all { it }) Controller.directHelp = true
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        IncomingShare.fromUris(uris, contentResolver)?.let { Controller.receiveShare(it) }
    }
    private val askForMicThenHandsFree = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAllowed = granted
        if (granted) switchHandsFree(true)
    }

    /** Hands-free (switched in Settings) needs the microphone, and the notification permission on Android 13+ (the listening notice is what keeps it allowed to run). */
    private fun switchHandsFree(on: Boolean) {
        val needsAsk = on && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            !on -> Controller.turnHandsFree(false)
            !micAllowed -> askForMicThenHandsFree.launch(Manifest.permission.RECORD_AUDIO)
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
        contactsAllowed = Contacts.allowed(this)
        if (!micAllowed) askForMic.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            VoiceAssistantPhoneTheme {
                BackHandler(enabled = settingsPage != null || historyOpen) { if (historyOpen) historyOpen = false else settingsPage = if (settingsPage.isNullOrEmpty()) null else "" }
                DiagnosisDialog()
                if (Controller.directHelp) {
                    val context = LocalContext.current
                    AlertDialog(
                        onDismissRequest = { Controller.directHelp = false },
                        title = { Text("Texts need one more step") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Android blocks texting for apps installed outside the Play Store until you allow it:")
                                Text("1. Tap “Open app info”.")
                                Text("2. Tap the ⋮ menu (top right) and choose “Allow restricted settings”.")
                                Text("3. Open Permissions and allow SMS and Phone.")
                                Text("4. Come back and switch “Send texts and place calls myself” on again.")
                                Text("Until then, texts and calls open your messaging app or dialler as before.", style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            Button({
                                Controller.directHelp = false
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
                            }) { Text("Open app info") }
                        },
                        dismissButton = { TextButton({ Controller.directHelp = false }) { Text("Not now") } },
                    )
                }
                LaunchedEffect(Controller.directPermissionAsk) {
                    if (Controller.directPermissionAsk) askForDirect.launch(PhoneAction.DIRECT_PERMISSIONS)
                }
                if (Controller.offlineDownloadAsk) AlertDialog(
                    onDismissRequest = { Controller.answerOfflineDownload(false) },
                    title = { Text("Download the offline helper?") },
                    text = { Text("It is a ${OnDeviceModel.DOWNLOAD_TEXT} download from Hugging Face, best on Wi-Fi. After that it works with no internet and nothing leaves your phone.") },
                    confirmButton = { Button({ Controller.answerOfflineDownload(true) }) { Text("Download") } },
                    dismissButton = { TextButton({ Controller.answerOfflineDownload(false) }) { Text("Not now") } },
                )
                Controller.pendingAction?.let { PhoneActionDialog(it, { askForContacts.launch(Manifest.permission.READ_CONTACTS) }, contactsAllowed) }
                if (Controller.pairing != null && !Controller.setupActive) Controller.incomingShare?.let { ShareSheet(it) }
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Box(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                        val page = settingsPage
                        when {
                            Controller.pairing == null || Controller.setupActive -> SetupScreen()
                            historyOpen -> HistoryScreen { historyOpen = false }
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
                            else -> TalkScreen(
                                micAllowed, { askForMic.launch(Manifest.permission.RECORD_AUDIO) },
                                onAttach = { pickFiles.launch(arrayOf("image/*", "video/*", "application/pdf", "text/*", "application/json")) },
                                onHistory = { historyOpen = true },
                                onHandsFree = ::switchHandsFree,
                            ) { section -> settingsPage = section }
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
    if (debuggable && intent?.getStringExtra("eval_id") != null) DebugEval.handle(this, intent) // measuring the on-device model, see OnDeviceModel.kt
    if (debuggable && intent?.getBooleanExtra("demo_camera", false) == true) { // lets `adb` open the camera screen without the assistant asking
        Controller.openDemoCamera()
        startActivity(android.content.Intent(this, CameraActivity::class.java))
    }
    if (!debuggable || encoded == null) return
    val code = runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)) }.getOrNull()
    android.util.Log.i("VoiceAssistant", "debug pairing accepted=${code?.let { Controller.pairFromText(it) }}")
}

@Composable
private fun TalkScreen(micAllowed: Boolean, askForMic: () -> Unit, onAttach: () -> Unit, onHistory: () -> Unit, onHandsFree: (Boolean) -> Unit, openSettings: (section: String) -> Unit) {
    val listState = rememberLazyListState()
    var typed by remember { mutableStateOf("") }
    val lines = Controller.lines
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length, lines.lastOrNull { it.card != null }?.card) { // a card arriving scrolls it into view too
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) { // imePadding: the keyboard must not cover the message box
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(Controller.status, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onHistory) { Text("History") }
            TextButton({ Controller.newChat() }) { Text("New chat") }
            TextButton({ openSettings("") }) { Text("Settings") }
        }
        if (!Controller.connected) {
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (Controller.offlineHelperOn) "Not connected to your PC. Type a request and the offline helper on this phone will try." else "Not connected to your PC",
                    Modifier.weight(1f).padding(end = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton({ Controller.runDiagnosis(context) }, enabled = !Controller.diagnosing) { Text(if (Controller.diagnosing) "Checking…" else "Find out why") }
            }
        }
        Controller.updateAvailable?.let { found ->
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Version ${found.version} is available.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                TextButton({ runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(found.url))) } }) { Text("See what's new") }
            }
        }
        // hands-free is switched on and set up in Settings (This phone); only a problem with it is worth showing here
        if (Controller.hfNotice.isNotBlank()) Text(Controller.hfNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines) { line -> Bubble(line, openSettings) }
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
            // it went off by mistake: drop what it heard, send nothing more
            if (Controller.phase == Phase.Listening || Controller.phase == Phase.Starting || Controller.phase == Phase.Sending) {
                OutlinedButton({ Controller.cancelListening() }) { Text("Cancel, that was a mistake") }
            }
            if (micAllowed && Controller.handsFree) {
                TextButton({ Controller.pauseListening() }) { Text("Mic off: stop listening for “${Controller.wakePhrase}”") }
            } else if (Controller.hfPaused) {
                Button({ onHandsFree(true) }) { Text("Mic is off. Turn listening back on") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onAttach) { Text("＋", style = MaterialTheme.typography.titleLarge) } // send a photo, video or file
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Or type a message") }, singleLine = true)
            Button({ Controller.sendText(typed); typed = "" }, enabled = Controller.connected || Controller.canAnswerOffline) { Text("Send") }
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
private fun Bubble(line: Line, openSection: (String) -> Unit) {
    if (line.text.isBlank() && line.media.isEmpty() && line.thinking.isBlank()) return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.fromUser) Alignment.End else Alignment.Start) {
        if (line.media.isNotEmpty()) MediaStrip(line.media)
        if (line.thinking.isNotBlank()) ThinkingBlock(line.thinking, still = line.text.isBlank())
        if (line.text.isNotBlank()) Text(
            if (line.fromUser) AnnotatedString(line.text) else AnswerCard.linkify(line.text, MaterialTheme.colorScheme.primary),
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (line.fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        line.card?.let { AnswerCardView(it, openSection) }
    }
}

/** The model's reasoning, folded under a one-line title: tap to read it. [still] is true until the answer itself starts. */
@Composable
private fun ThinkingBlock(text: String, still: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(
            (if (open) "▾ " else "▸ ") + if (still) "Thinking…" else "Thought",
            Modifier.clickable { open = !open }.padding(vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
        )
        if (open) Text(
            text.trim(),
            Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
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

/** What the user sent with a message: pictures and a frame of each video (with a play mark), other files as a name. */
@Composable
private fun MediaStrip(items: List<SharedItem>) {
    val context = LocalContext.current
    Column(Modifier.padding(bottom = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val thumb by produceState<android.graphics.Bitmap?>(null, item.uri) { value = withContext(Dispatchers.IO) { Thumbnails.load(context, item) } }
            val picture = thumb
            if (picture != null) {
                Box(Modifier.clip(RoundedCornerShape(14.dp))) {
                    Image(picture.asImageBitmap(), contentDescription = item.name, Modifier.widthIn(max = 240.dp).heightIn(max = 240.dp))
                    if (item.kind == "video") Text("▶", Modifier.align(Alignment.Center).clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            } else {
                Text(
                    (if (item.kind == "video") "🎬  " else if (item.kind == "PDF") "📄  " else "📎  ") + item.name,
                    Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
