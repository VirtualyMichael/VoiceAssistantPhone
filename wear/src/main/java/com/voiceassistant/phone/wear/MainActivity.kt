package com.voiceassistant.phone.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * The watch screen: a big microphone button, the state in words, what the assistant heard and its reply, and Yes/No when the assistant asks.
 * Everything goes through the phone app (see docs/WATCH.md); the watch never reaches the PC itself.
 */
class MainActivity : ComponentActivity() {
    private lateinit var phone: PhoneLink
    private val recorder = Recorder()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = PhoneLink(this)
        val startTalking = intent?.getBooleanExtra(EXTRA_TALK, false) == true // opened by the tile or the complication, which mean "ask now"
        setContent {
            val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) toggleTalking() }
            val tryTalk = {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleTalking()
                else askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            talkNow = tryTalk
            LaunchedEffect(Unit) {
                phone.hello()
                if (startTalking && !recorder.active) tryTalk()
            }
            LaunchedEffect(WatchModel.recording) { // the screen stays on while the microphone is open, so a long question is not cut off by the watch sleeping
                if (WatchModel.recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme {
                Screen(
                    onMic = tryTalk,
                    onYes = { phone.answer(true); WatchModel.clearAsk() },
                    onNo = { phone.answer(false); WatchModel.clearAsk() },
                    onStop = { phone.stop() },
                    onNewChat = { phone.newChat(); WatchModel.newChat() },
                    onQuick = { text -> phone.quick(text); WatchModel.sentQuick(text); buzz(30) },
                )
            }
        }
    }

    private var talkNow: (() -> Unit)? = null

    /** The tile or complication was tapped while the app was already open: start listening in the running screen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TALK, false) && !recorder.active) talkNow?.invoke()
    }

    /** A short buzz when the microphone opens and closes, so you can tell without looking. */
    private fun buzz(ms: Long) {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Press to start the question, press again to finish it. */
    private fun toggleTalking() {
        if (recorder.active) {
            recorder.stop() // the recording thread closes the stream; the phone is told "that was all" a moment later, once the last audio has arrived
            buzz(30)
            return
        }
        buzz(60)
        WatchModel.recording = true
        phone.talkStart()
        phone.openAudio { out ->
            if (out == null || !recorder.start(out) { main.postDelayed({ phone.talkEnd(); WatchModel.recording = false }, 450) }) {
                phone.talkEnd()
                WatchModel.recording = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        recorder.stop() // never keep the microphone open once the screen is gone
    }

    companion object {
        /** Start the app already listening (the tile and the complication use this). */
        const val EXTRA_TALK = "talk"
    }
}

/** One-tap answers that need no microphone: the phone sends them to the assistant as typed text. */
private val QUICK_REPLIES = listOf("Yes", "No", "Thanks", "Continue", "Say that again", "Shorter please")

@Composable
private fun Screen(onMic: () -> Unit, onYes: () -> Unit, onNo: () -> Unit, onStop: () -> Unit, onNewChat: () -> Unit, onQuick: (String) -> Unit) {
    val ask = WatchModel.ask
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Box(Modifier.padding(top = 18.dp)) { TimeText() } }
        item { Text(WatchModel.phaseLabel(), textAlign = TextAlign.Center, style = MaterialTheme.typography.caption1) }
        if (ask != null) {
            item { Text(ask.prompt, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onNo, Modifier.size(ButtonDefaults.DefaultButtonSize), colors = ButtonDefaults.secondaryButtonColors()) { Text("No") }
                    Button(onYes, Modifier.size(ButtonDefaults.DefaultButtonSize)) { Text("Yes") }
                }
            }
        } else {
            item {
                Button(onMic, Modifier.size(ButtonDefaults.LargeButtonSize)) { Text(if (WatchModel.recording) "Done" else "Talk") }
            }
            if (WatchModel.heard.isNotBlank()) item { Text(WatchModel.heard, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2, modifier = Modifier.padding(horizontal = 10.dp)) }
            if (WatchModel.reply.isNotBlank()) item { Text(WatchModel.reply, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) }
            if (WatchModel.phase == "speaking" || WatchModel.phase == "thinking") {
                item { Chip(onClick = onStop, label = { Text("Stop") }, colors = ChipDefaults.secondaryChipColors()) }
            }
            items(QUICK_REPLIES) { text -> Chip(onClick = { onQuick(text) }, label = { Text(text) }, colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = onNewChat, label = { Text("New chat") }, colors = ChipDefaults.secondaryChipColors()) }
        }
    }
}
