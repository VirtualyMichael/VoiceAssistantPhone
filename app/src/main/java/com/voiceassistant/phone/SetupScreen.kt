package com.voiceassistant.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val STEPS = 5

/** The first-run guide: Tailscale, pairing, permissions, a first try. Each step checks itself and explains in plain words. */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var ticks by remember { mutableIntStateOf(0) }
    // the checks look at the phone itself (is Tailscale installed and on, was a permission just allowed), so look again every second
    LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
    fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ticks++ }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ticks++; if (granted && step == 2) scanning = true }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ticks++ }

    if (scanning) {
        QrScanner(
            onCode = { text ->
                scanning = false
                if (!Controller.pairFromText(text)) Controller.reportPairProblem("That picture isn't a pairing code from the assistant. Show a new code on your PC and try again.")
            },
            onClose = { scanning = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, Modifier.fillMaxWidth())
        val installed = remember(ticks) { Environment.tailscaleInstalled(context) }
        val vpn = remember(ticks) { Environment.vpnActive(context) }
        val micOk = remember(ticks) { has(Manifest.permission.RECORD_AUDIO) }
        val cameraOk = remember(ticks) { has(Manifest.permission.CAMERA) }
        val notifyOk = remember(ticks) { Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS) }

        when (step) {
            0 -> {
                Title("Set up your assistant")
                Body("This takes about five minutes, once. You'll connect this phone to the assistant on your PC, then allow what the app needs.")
                Body("You need:")
                Bullet("Your PC, with the assistant running.")
                Bullet("A free Tailscale account. It's a private link between your own devices.")
                Help("Why Tailscale?", "Tailscale makes a private, encrypted connection between your phone and your PC, so you can talk to your PC from anywhere without opening anything to the internet. Only devices signed in to your account can use it.")
                PrimaryRow(next = "Start", onNext = { step = 1 })
            }
            1 -> {
                Title("Install Tailscale")
                Body("Install Tailscale on this phone and on your PC, and sign in to the same account on both.")
                Check(installed, "Tailscale is installed on this phone")
                Check(vpn, "Tailscale is turned on")
                if (!installed) Button({ Environment.installTailscale(context) }) { Text("Get Tailscale") }
                else if (!vpn) Button({ Environment.openTailscale(context) }) { Text("Open Tailscale") }
                Help("I'm not sure it's on", "Open the Tailscale app. It should say Connected. Android also shows a small key icon in the status bar while it is on.")
                PrimaryRow(next = if (installed && vpn) "Next" else "Skip", onNext = { step = 2 }, onBack = { step = 0 }, strong = installed && vpn)
            }
            2 -> {
                Title("Pair with your PC")
                Body("1. On your PC open the assistant and go to Settings, then Phone.")
                Body("2. Press “Turn on the phone link”, then “Pair a new phone”. A picture appears.")
                Body("3. Tap the button below and point your camera at it.")
                val name = Controller.pairing?.name
                when {
                    Controller.pairBusy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text("Connecting…")
                    }
                    Controller.connected && name != null -> Check(true, "Connected to $name")
                }
                if (Controller.pairProblem.isNotBlank() && !Controller.pairBusy) {
                    Text(Controller.pairProblem, color = MaterialTheme.colorScheme.error)
                }
                Button({ if (cameraOk) scanning = true else askCamera.launch(Manifest.permission.CAMERA) }, enabled = !Controller.pairBusy) { Text("Scan the code") }
                OutlinedButton({ typing = true }, enabled = !Controller.pairBusy) { Text("Type the code instead") }
                Help("The scan doesn't work", "Make the picture on the PC larger or move the phone a little further back. Or tap “Type the code instead” and enter the address and the code shown on the PC.")
                PrimaryRow(next = "Next", onNext = { step = 3 }, onBack = { step = 1 }, enabled = Controller.connected, strong = Controller.connected)
            }
            3 -> {
                Title("Allow what it needs")
                Permission("Microphone", "So you can talk to it. Required.", micOk) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                Permission("Notifications", "So hands-free can keep listening with the screen off. Android needs a visible notice for that.", notifyOk) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Permission("Camera", "So it can look at things for you when you ask “what am I looking at?”.", cameraOk) { askCamera.launch(Manifest.permission.CAMERA) }
                Help("Is it safe?", "The microphone is only used while you hold the Talk button, or in hands-free mode, where nothing leaves the phone until you say your wake phrase. Photos are taken only when you tap the shutter.")
                PrimaryRow(next = "Next", onNext = { step = 4 }, onBack = { step = 2 }, enabled = micOk, strong = micOk)
            }
            else -> {
                Title("You're ready")
                Body("Hold the big Talk button and ask something, like “What time is it?”")
                Body("For hands-free, switch on Hands-free on the main screen and say “${Controller.wakePhrase}”.")
                OutlinedButton({ Controller.runDiagnosis(context) }, enabled = !Controller.diagnosing) { Text(if (Controller.diagnosing) "Checking…" else "Test the connection") }
                PrimaryRow(next = "Finish", onNext = { Controller.finishSetup() }, onBack = { step = 3 })
            }
        }
    }

    if (typing) TypeCodeDialog(onDismiss = { typing = false }) { text ->
        typing = false
        if (!Controller.pairFromText(text)) Controller.reportPairProblem("That doesn't look like a pairing code. Type the PC's address and the code, like 100.1.2.3:47830 ABCD-EFGH-JK.")
    }
}

@Composable
private fun TypeCodeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On the PC's pairing screen you'll see the code. Type the PC's address (shown there) and the code, or paste the pairing text.")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("100.1.2.3:47830 ABCD-EFGH-JK") }, minLines = 2)
            }
        },
        confirmButton = { Button({ onSubmit(text) }, enabled = text.isNotBlank()) { Text("Connect") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))

@Composable
private fun Check(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Permission(name: String, why: String, granted: Boolean, request: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge) else OutlinedButton(request) { Text("Allow") }
    }
}

@Composable
private fun Help(question: String, answer: String) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton({ open = !open }) { Text("? $question") }
        if (open) Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PrimaryRow(next: String, onNext: () -> Unit, onBack: (() -> Unit)? = null, enabled: Boolean = true, strong: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onBack) { Text("‹ Back") } else Box(Modifier)
        if (strong) Button(onNext, enabled = enabled) { Text(next) } else OutlinedButton(onNext, enabled = enabled) { Text(next) }
    }
}
