package com.voiceassistant.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.math.sqrt

/** [media] are the pictures, videos and files sent with a message from the user; they show above its text. */
data class Line(val fromUser: Boolean, val text: String, val card: AnswerCard? = null, val media: List<SharedItem> = emptyList())
data class Approval(val id: String, val prompt: String)
/** One saved chat on the PC, for the history screen. [updated] is seconds since 1970. */
data class ChatSummary(val id: String, val title: String, val updated: Long, val count: Int)
/** The assistant's request for the camera. mode is "photo", or "video" with the limits below (the PC's quality level: normal, mid or high). */
data class CameraRequest(
    val id: String, val prompt: String, val auto: Boolean,
    val mode: String = "photo", val maxSeconds: Int = 15, val maxFrames: Int = 100, val maxEdge: Int = 640, val maxFps: Float = 2f, val maxBytes: Long = 5_000_000L, val audio: Boolean = false,
    val detailFrames: Int = 0, val detailEdge: Int = 1280,
) {
    val isVideo get() = mode == "video"
}

/** A short message at the bottom of the settings screen. [undoKey] is set when the change can be taken back. */
data class SettingsNote(val text: String, val undoKey: String? = null, val undoValue: Any? = null, val error: Boolean = false)

/** The one thing the user should be able to read off the screen at any moment. */
enum class Phase(val label: String) {
    Offline("Waiting for your PC…"),
    Armed(""), // the label is built from the wake phrase
    Idle("Hold to talk"),
    Starting("One moment…"),
    Listening("Listening. Speak now"),
    Sending("Got it…"),
    Thinking("Thinking…"),
    Speaking("Speaking…"),
}

/** Everything the screen shows, plus the push-to-talk logic. One instance for the whole app, so turning the phone does not drop the link. */
object Controller : RemoteListener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var client: RemoteClient? = null
    private var mic: Mic? = null
    private val player = Player()
    private var started = false
    private lateinit var appContext: Context
    private var detectors: SherpaDetectors? = null
    private var engine: HandsFreeEngine? = null
    private var keywordLines = ""
    private var visibleActivities = 0
    private var wantConnected = false
    private var waitingForListening = false
    private var talkEndedAt = 0L
    private var frames = 0
    private var gotWords = false
    // "Got it…" is only ever a short in-between state: whatever happened, it ends a few seconds after you let go.
    private val noWordsCheck = Runnable {
        sent = false
        if (!gotWords) notice = "I didn't catch anything. Hold the button while you speak."
    }

    var pairing by mutableStateOf<Pairing?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf("Not paired yet")
        private set
    var assistantState by mutableStateOf("idle")
        private set
    var talking by mutableStateOf(false)
        private set
    var micOpen by mutableStateOf(false)
        private set
    var sent by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var partial by mutableStateOf("")
        private set
    var notice by mutableStateOf("")
        private set
    var latency by mutableStateOf("")
        private set
    var approval by mutableStateOf<Approval?>(null)
        private set
    val lines = mutableStateListOf<Line>()

    /** True while the first-run setup guide is showing (also when run again from the settings). */
    var setupActive by mutableStateOf(false)
        private set
    var pairBusy by mutableStateOf(false)
        private set
    var pairProblem by mutableStateOf("")
        private set
    var diagnosis by mutableStateOf<Diagnosis?>(null)
        private set
    var diagnosing by mutableStateOf(false)
        private set
    private var pendingRequest: PairRequest? = null
    private var pcKnowsPhone = true

    /** The PC's settings, as last received. */
    var settings by mutableStateOf<SettingsSnapshot?>(null)
        private set
    var settingsNote by mutableStateOf<SettingsNote?>(null)
        private set
    var hapticsOn by mutableStateOf(true)
        private set
    var cameraTimerDefault by mutableStateOf(false)
        private set
    /** Send the PC what the phone knows about a picture or video (when it was taken, with what, how long), and optionally where. */
    var shareDetails by mutableStateOf(true)
        private set
    var sharePlaces by mutableStateOf(false)
        private set
    /** Texts and calls are sent from this phone itself after the user's Yes, instead of opening the messaging app or dialler. */
    var directSend by mutableStateOf(false)
        private set
    /** True while Android's permission question for [directSend] should be on screen (the activity asks it). */
    var directPermissionAsk by mutableStateOf(false)
    /** Android refused the SMS or phone permission (apps installed outside the Play Store need "Allow restricted settings" first): show the steps. */
    var directHelp by mutableStateOf(false)
    val directReady: Boolean get() = directSend && PhoneAction.DIRECT_PERMISSIONS.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    /** What the next message from the user shows above its text: the question as typed, with the things sent along with it. */
    private var shareBubble: Pair<String, List<SharedItem>>? = null
    private val previousValues = mutableMapOf<String, Any?>()

    /** The PC's settings are requested again whenever the screen opens or the link comes back. */
    fun loadSettings() {
        if (connected) client?.getSettings()
    }

    fun changeSetting(key: String, value: Any) {
        val current = settings ?: return
        previousValues[key] = current.find(key)?.value
        settings = current.withValue(key, value) // show it at once; the PC's answer confirms or puts it back
        client?.setSetting(key, value)
    }

    fun undoSetting() {
        val note = settingsNote ?: return
        val key = note.undoKey ?: return
        note.undoValue?.let { changeSetting(key, it) }
        settingsNote = null
    }

    fun clearSettingsNote(note: SettingsNote) {
        if (settingsNote == note) settingsNote = null
    }

    fun showSettingsNote(text: String, error: Boolean = false) {
        settingsNote = SettingsNote(text, error = error)
    }

    /** Settings that live on this phone, not on the PC. */
    fun phoneItems(): List<SettingItem> {
        val where = "This phone"
        return listOf(
            SettingItem("phone.handsfree", "Hands-free", "Say your wake phrase and ask, even with the screen off. Needs the microphone and shows a notification while it listens.",
                "toggle", value = handsFree, default = false, sectionTitle = where, essential = true),
            SettingItem("phone.wake_phrase", "Wake phrase", "The words that start it listening. Plain words work best, like “Hey Jarvis”, and longer phrases are more reliable.",
                "text", value = wakePhrase, default = "Hey Jarvis", maxLength = 40, sectionTitle = where),
            SettingItem("phone.sensitivity", "Wake sensitivity", "High hears you from further away but may start by mistake. Low is stricter.",
                "choice", value = sensitivity.toString(), default = "1",
                choices = listOf(SettingChoice("0", "Low"), SettingChoice("1", "Normal"), SettingChoice("2", "High")), sectionTitle = where),
            SettingItem("phone.camera_timer", "Camera timer", "Start the camera with the 3-second timer already on.", "toggle",
                value = cameraTimerDefault, default = false, sectionTitle = where),
            SettingItem("phone.haptics", "Vibrate on buttons", "A short buzz when you press Talk and when the microphone opens.", "toggle",
                value = hapticsOn, default = true, sectionTitle = where),
            SettingItem("phone.notifications",
                if (readNotifications && !NotificationReader.accessGranted(appContext)) "Read my notifications (needs access: tap to allow)" else "Read my notifications",
                "Lets the assistant read the notifications on this phone when you ask, for example “what did I miss?”. They go to your PC only, and only when you ask. " +
                    "Android also needs notification access for this app; turning this on opens that page.",
                "toggle", value = readNotifications && NotificationReader.accessGranted(appContext), default = false, sectionTitle = where),
            SettingItem("phone.share_details", "Tell the assistant about photos and videos",
                "When you send a picture or video, also send when it was taken, with what camera and how long it is, so you can ask things like “when was this taken?”. It goes to your PC only.",
                "toggle", value = shareDetails, default = true, sectionTitle = where),
            SettingItem("phone.share_places", "Include where they were taken",
                "Adds the GPS position stored in a picture or video, when it has one, so you can ask “where was this?”. Many apps remove it before sharing. Off by default.",
                "toggle", value = sharePlaces, default = false, sectionTitle = where),
            SettingItem("phone.direct_send", "Send texts and place calls myself",
                "When you say Yes to a text or call, this app sends the text or starts the call itself instead of opening the messaging app or dialler. You still see who and what first and must tap Yes. " +
                    "Android asks for permission to send texts and make calls.",
                "toggle", value = directSend, default = false, sectionTitle = where),
            SettingItem("phone.watch", "Let my watch talk to the assistant",
                "Lets the Voice Assistant app on your Wear OS watch ask questions through this phone and show the answers. The watch never reaches your PC itself. Off by default.",
                "toggle", value = watchEnabled, default = false, sectionTitle = where),
            SettingItem("phone.offline", if (offlineProgress >= 0) "Offline helper (downloading: $offlineProgress%)" else "Offline helper",
                "When your PC is out of reach, typed requests like alarms, timers, texts, calls and simple questions are handled by a small AI model on this phone. " +
                    "It is much less capable than the one on your PC and cannot search the web. Needs a one-time ${OnDeviceModel.DOWNLOAD_TEXT} download; everything stays on this phone.",
                "toggle", value = offlineHelperOn || offlineProgress >= 0, default = false, sectionTitle = where),
        )
    }

    /** The user allows the assistant to read this phone's notifications (Android's notification access is needed as well). */
    var readNotifications by mutableStateOf(false)
        private set

    /** Changes to [phoneItems] (hands-free goes through the screen, because it may need to ask for permissions). */
    fun changePhoneSetting(key: String, value: Any) {
        when (key) {
            "phone.wake_phrase" -> changeWakePhrase(value.toString())
            "phone.sensitivity" -> changeSensitivity(value.toString().toIntOrNull() ?: 1)
            "phone.camera_timer" -> { cameraTimerDefault = value == true; prefs.edit().putBoolean("camera_timer", cameraTimerDefault).apply() }
            "phone.haptics" -> { hapticsOn = value == true; prefs.edit().putBoolean("haptics", hapticsOn).apply() }
            "phone.share_details" -> { shareDetails = value == true; prefs.edit().putBoolean("share_details", shareDetails).apply() }
            "phone.share_places" -> { sharePlaces = value == true; prefs.edit().putBoolean("share_places", sharePlaces).apply() }
            "phone.direct_send" -> {
                directSend = value == true
                prefs.edit().putBoolean("direct_send", directSend).apply()
                if (directSend && !directReady) directPermissionAsk = true
            }
            "phone.watch" -> changeWatch(value == true)
            "phone.offline" -> if (offlineProgress < 0) changeOfflineHelper(value == true)
            "phone.notifications" -> {
                readNotifications = value == true
                prefs.edit().putBoolean("read_notifications", readNotifications).apply()
                if (readNotifications && !NotificationReader.accessGranted(appContext)) {
                    showSettingsNote("Turn on notification access for Voice Assistant, then come back.")
                    runCatching {
                        appContext.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    /** Find the most likely reason the phone is not connected, for the "Test the connection" button. */
    fun runDiagnosis(context: Context) {
        if (diagnosing) return
        diagnosing = true
        Thread {
            val p = pairing
            val facts = Facts(
                paired = p != null, connected = connected,
                tailscaleInstalled = Environment.tailscaleInstalled(context), vpnActive = Environment.vpnActive(context),
                pcKnowsPhone = pcKnowsPhone, pcReachable = p?.let { Environment.canReach(it.host, it.port) },
            )
            val result = ConnectionDoctor.diagnose(facts)
            main.post { diagnosis = result; diagnosing = false }
        }.start()
    }

    fun clearDiagnosis() { diagnosis = null }

    /** Debug builds only: lets `adb` open the camera screen without the assistant asking, to test it. */
    fun openDemoCamera() { camera = CameraRequest("demo", "Demo camera", false) }

    /** The photo the assistant has asked for; the camera screen shows while this is set. */
    var camera by mutableStateOf<CameraRequest?>(null)
        private set

    /** Hands-free: the user switched it on. */
    var handsFree by mutableStateOf(false)
        private set
    var hfState by mutableStateOf(HfState.Off)
        private set
    var wakePhrase by mutableStateOf("Hey Jarvis")
        private set
    var sensitivity by mutableStateOf(1) // 0 low, 1 normal, 2 high
        private set
    var hfNotice by mutableStateOf("")
        private set

    private val thresholds = floatArrayOf(0.2f, 0.12f, 0.07f)

    val phaseLabel: String
        get() = if (phase == Phase.Armed) "Say “$wakePhrase”" else phase.label

    val phase: Phase
        get() = when {
            !connected -> Phase.Offline
            handsFree && hfState == HfState.Capturing -> Phase.Listening
            handsFree && hfState == HfState.Waiting && assistantState == "speaking" -> Phase.Speaking
            handsFree && hfState == HfState.Waiting && assistantState == "thinking" -> Phase.Thinking
            handsFree && hfState == HfState.Waiting -> Phase.Sending
            handsFree && hfState == HfState.Armed -> Phase.Armed
            talking && !micOpen -> Phase.Starting
            talking -> Phase.Listening
            sent && assistantState != "thinking" && assistantState != "speaking" -> Phase.Sending
            assistantState == "thinking" -> Phase.Thinking
            assistantState == "speaking" -> Phase.Speaking
            else -> Phase.Idle
        }

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)
        wakePhrase = prefs.getString("wake_phrase", wakePhrase) ?: wakePhrase
        sensitivity = prefs.getInt("sensitivity", 1).coerceIn(0, 2)
        if (prefs.getString("kw_key", "") == keywordKey()) keywordLines = prefs.getString("kw_lines", "") ?: ""
        hapticsOn = prefs.getBoolean("haptics", true)
        shareDetails = prefs.getBoolean("share_details", true)
        sharePlaces = prefs.getBoolean("share_places", false)
        directSend = prefs.getBoolean("direct_send", false)
        watchEnabled = prefs.getBoolean("watch", false)
        WatchLink.init(appContext)
        WatchLink.enabled = watchEnabled
        cameraTimerDefault = prefs.getBoolean("camera_timer", false)
        readNotifications = prefs.getBoolean("read_notifications", false)
        offlineHelperOn = prefs.getBoolean("offline_helper", false) && OnDeviceModel.available(appContext)
        handsFree = prefs.getBoolean("handsfree", false) &&
            appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val stored = Pairing.parse(prefs.getString("pairing", "") ?: "")
        if (stored != null) prefs.edit().putBoolean("setup_done", true).apply() // someone who paired before the guide existed does not need it
        setupActive = stored == null && !prefs.getBoolean("setup_done", false)
        stored?.let { pair(it, save = false) }
    }

    fun startSetup() { pairProblem = ""; setupActive = true }

    fun reportPairProblem(text: String) { pairBusy = false; pairProblem = text }

    fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        setupActive = false
    }

    // ---- hands-free ----------------------------------------------------------------
    private fun keywordKey() = "$wakePhrase|$sensitivity"

    fun turnHandsFree(on: Boolean) {
        handsFree = on
        prefs.edit().putBoolean("handsfree", on).apply()
        hfNotice = ""
        if (on) {
            startHandsFreeWhenReady()
        } else {
            appContext.stopService(Intent(appContext, ListenService::class.java))
            detachHandsFree()
        }
    }

    fun changeWakePhrase(phrase: String) {
        val clean = phrase.trim().take(40)
        if (clean.isBlank() || clean == wakePhrase) return
        wakePhrase = clean
        prefs.edit().putString("wake_phrase", clean).apply()
        refreshKeywords()
    }

    fun changeSensitivity(level: Int) {
        sensitivity = level.coerceIn(0, 2)
        prefs.edit().putInt("sensitivity", sensitivity).apply()
        refreshKeywords()
    }

    /** The PC turns the phrase into the wake model's token format. Asked again whenever the phrase or sensitivity changes. */
    private fun refreshKeywords() {
        keywordLines = ""
        if (handsFree && connected) client?.getKeywords(wakePhrase, thresholds[sensitivity])
    }

    private fun startHandsFreeWhenReady() {
        if (keywordLines.isNotBlank()) {
            ContextCompat.startForegroundService(appContext, Intent(appContext, ListenService::class.java))
        } else if (connected) {
            client?.getKeywords(wakePhrase, thresholds[sensitivity])
        } // otherwise it is requested as soon as the PC answers hello
    }

    /** Called by the listening service: build the detectors and start the loop. */
    fun attachHandsFree(context: Context): Boolean {
        if (keywordLines.isBlank()) return false
        return try {
            detachHandsFree()
            val d = SherpaDetectors(context, keywordLines)
            detectors = d
            engine = HandsFreeEngine(d, sink).also { it.start() }
            player.assistantAudio = true
            true
        } catch (t: Throwable) {
            android.util.Log.e("VoiceAssistant", "hands-free could not start", t)
            main.post { hfNotice = "Hands-free could not start: ${t.message}"; handsFree = false }
            false
        }
    }

    fun detachHandsFree() {
        engine?.stop(); engine = null
        detectors?.release(); detectors = null
        player.assistantAudio = false
        main.post { hfState = HfState.Off }
    }

    /** One 30 ms microphone frame from the listening service. */
    fun onHandsFreeFrame(frame: ByteArray) {
        val e = engine ?: return
        e.process(frame)
        if (e.state == HfState.Capturing && ++frames % 2 == 0) {
            val v = rms(frame)
            main.post { level = (level * 0.5f + v * 0.5f).coerceIn(0f, 1f) }
        }
    }

    private val sink = object : HandsFreeSink {
        override fun sendAudio(frame: ByteArray) { client?.sendAudio(frame) }
        override fun wake() {
            client?.wake()
            main.post { partial = ""; notice = ""; hfNotice = ""; latency = ""; gotWords = false; sent = false }
        }
        override fun endOfSpeech() {
            client?.endOfSpeech()
            talkEndedAt = SystemClock.elapsedRealtime()
            main.post { level = 0f; sent = true; main.removeCallbacks(noWordsCheck); main.postDelayed(noWordsCheck, 4000) }
        }
        override fun interrupt() { client?.interrupt() }
        override fun flushPlayback() { player.flush() }
        override fun changed() { main.post { hfState = engine?.state ?: HfState.Off } }
        override fun noSpeech() { main.post { notice = "I didn't hear a question. Say “$wakePhrase”, then ask." } }
    }

    /** Remember the PC and connect to it. Returns false when the pasted text is not a pairing code. */
    fun pairFromText(text: String): Boolean {
        when (val input = PairingText.parse(text)) {
            is PairingInput.Token -> pair(input.pairing, save = true)
            is PairingInput.Code -> startPairing(PairRequest(input.host, input.port, input.code, input.name))
            PairingInput.Invalid -> return false
        }
        return true
    }

    /** Redeem a one-time code. The PC answers with this phone's own token (see onPaired); a wrong or old code is not retried. */
    private fun startPairing(request: PairRequest) {
        disconnect()
        wantConnected = false
        pairProblem = ""
        pairBusy = true
        pendingRequest = request
        status = "Pairing with ${request.name}…"
        client = RemoteClient(request, this).also { it.connect() }
    }

    private fun pair(p: Pairing, save: Boolean) {
        if (save) {
            prefs.edit().putString("pairing", p.toJson()).apply()
            finishSetup() // a PC paired with its permanent token (the command line, not the QR code) needs no guide
        }
        pairing = p
        wantConnected = true
        connect()
    }

    /** Remove this PC. The PC is told too, so it takes this phone off its list of paired phones. */
    fun forget() {
        client?.forgetMe()
        prefs.edit().remove("pairing").apply()
        wantConnected = false
        disconnect()
        pairing = null
        settings = null
        lines.clear()
        status = "Not paired yet"
        startSetup()
    }

    private fun connect() {
        val p = pairing ?: return
        disconnect()
        status = "Connecting to ${p.name}…"
        client = RemoteClient(p, this).also { it.connect() }
    }

    private fun disconnect() {
        if (camera != null && camera?.id != "demo") main.post { closeCamera() } // a lost link cancels a real request, not the debug demo
        engine?.onDisconnected()
        mic?.stop(); mic = null
        client?.close(); client = null
        main.removeCallbacks(noWordsCheck)
        connected = false
        pushWatchState()
        talking = false
        micOpen = false
        sent = false
        level = 0f
        waitingForListening = false
    }

    // ---- push to talk --------------------------------------------------------------
    fun pressTalk() {
        engine?.let { if (connected) it.manualPress(); return }
        val c = client ?: return
        if (!connected) return
        if (assistantState == "speaking" || assistantState == "thinking") {
            player.flush()
            c.interrupt()
        }
        main.removeCallbacks(noWordsCheck)
        talking = true
        sent = false
        gotWords = false
        notice = ""
        latency = ""
        partial = ""
        c.wake()
        if (assistantState == "listening") startMic() else waitingForListening = true
    }

    fun releaseTalk() {
        engine?.let { it.manualRelease(); return }
        if (!talking) return
        talking = false
        waitingForListening = false
        // keep recording a moment so the end of the last word is not clipped
        main.postDelayed({
            if (mic != null) {
                mic?.stop(); mic = null
                micOpen = false
                level = 0f
                client?.endOfSpeech()
                talkEndedAt = SystemClock.elapsedRealtime()
                sent = true
                main.postDelayed(noWordsCheck, 4000)
            }
        }, 350)
    }

    private fun startMic() {
        waitingForListening = false
        if (mic != null) return
        val m = Mic { frame ->
            client?.sendAudio(frame)
            if (++frames % 2 == 0) {
                val rms = rms(frame)
                main.post { level = (level * 0.5f + rms * 0.5f).coerceIn(0f, 1f) }
            }
        }
        if (m.start()) {
            mic = m
            micOpen = true
        } else {
            status = "Could not open the microphone"
        }
    }

    private fun rms(frame: ByteArray): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val v = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            sum += v * v
            i += 2
        }
        return (sqrt(sum / (frame.size / 2)) * 6).toFloat() // speech sits low in the range, so scale it up for the ring
    }

    fun stopSpeaking() {
        player.flush()
        client?.interrupt()
        engine?.onPcState("idle") // the engine goes back to waiting for the wake phrase
        main.removeCallbacks(noWordsCheck)
        sent = false
        partial = ""
        notice = ""
    }

    // ---- the PC's saved chats ------------------------------------------------------------------
    var chats by mutableStateOf<List<ChatSummary>>(emptyList())
        private set
    var currentChatId by mutableStateOf("")
        private set
    var chatsLoading by mutableStateOf(false)
        private set

    fun loadChats() {
        if (!connected) return
        chatsLoading = true
        client?.listChats()
    }

    /** Open a saved chat: the PC continues from it, and its messages replace what is shown here. */
    fun openChat(id: String) {
        if (connected) client?.openChat(id) else notice = "Connect to your PC to open an old chat."
    }

    fun deleteChat(id: String) {
        if (connected) client?.deleteChat(id)
    }

    // ---- the watch ----------------------------------------------------------------------------
    /** The user allowed their watch to talk to the assistant through this phone (Settings, This phone). Off by default. */
    var watchEnabled by mutableStateOf(false)
        private set
    private var watchTalking = false
    private val watchAudioWaiting = ArrayDeque<ByteArray>()

    private fun changeWatch(on: Boolean) {
        watchEnabled = on
        WatchLink.enabled = on
        prefs.edit().putBoolean("watch", on).apply()
        if (on) pushWatchState()
    }

    private fun pushWatchState() {
        WatchLink.state(if (connected) assistantState else "offline", connected, offlineHelperOn)
    }

    /** The watch app opened: tell it where things stand. */
    fun watchHello() = WatchLink.hello(if (connected) assistantState else "offline", connected, offlineHelperOn)

    /** The watch's button went down: the assistant starts listening, and the watch streams the audio (see [watchAudio]). */
    fun watchTalkStart() {
        val c = client
        if (c == null || !connected) {
            WatchLink.reply("I can't reach your PC.")
            return
        }
        if (assistantState == "speaking" || assistantState == "thinking") {
            player.flush()
            c.interrupt()
        }
        watchAudioWaiting.clear()
        watchTalking = true
        sent = false
        gotWords = false
        partial = ""
        c.wake()
    }

    /** One frame of the watch's microphone. The PC takes audio only once it says it is listening, so early frames wait (a second at most). */
    fun watchAudio(frame: ByteArray) {
        main.post {
            if (!watchTalking) return@post
            if (assistantState == "listening") {
                flushWatchAudio()
                client?.sendAudio(frame)
            } else if (watchAudioWaiting.size < 34) {
                watchAudioWaiting.addLast(frame)
            }
        }
    }

    private fun flushWatchAudio() {
        while (watchAudioWaiting.isNotEmpty()) client?.sendAudio(watchAudioWaiting.removeFirst())
    }

    fun watchTalkEnd() {
        if (!watchTalking) return
        watchTalking = false
        if (assistantState == "listening") flushWatchAudio()
        watchAudioWaiting.clear()
        client?.endOfSpeech()
        talkEndedAt = SystemClock.elapsedRealtime()
        sent = true
    }

    /** Yes or no from the watch. Texts and calls need the phone (who exactly), so only questions that need no choice are answered here. */
    fun watchAnswer(ok: Boolean) {
        val action = pendingAction
        when {
            approval != null -> answer(ok)
            action != null && !action.needsRecipient -> answerAction(appContext, ok)
        }
    }

    /** The New chat button: forget this conversation here and on the PC (the old one stays in the PC's history). */
    fun newChat() {
        lines.clear()
        shareBubble = null
        clearSettingsQuestion()
        if (connected) client?.newChat()
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (!connected && canAnswerOffline) askOffline(text.trim()) else client?.sendText(text.trim())
    }

    // ---- the offline helper: the model on the phone answers simple requests when the PC is out of reach ----
    /** The user turned the offline helper on (Settings, This phone). It needs the model file, which is downloaded once. */
    var offlineHelperOn by mutableStateOf(false)
        private set
    /** Ask before the 1.9 GB download starts. */
    var offlineDownloadAsk by mutableStateOf(false)
        private set
    /** 0..100 while the model downloads, -1 otherwise. */
    var offlineProgress by mutableIntStateOf(-1)
        private set
    var offlineBusy by mutableStateOf(false)
        private set
    private val offlineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val canAnswerOffline: Boolean get() = offlineHelperOn && !offlineBusy && OnDeviceModel.available(appContext)

    private fun askOffline(text: String) {
        lines.add(Line(true, text))
        offlineBusy = true
        offlineScope.launch {
            val raw = runCatching { OnDeviceModel.ask(appContext, text, OfflineHelper.system()).first }.getOrElse { "" }
            val result = OfflineHelper.parse(raw)
            main.post {
                offlineBusy = false
                when (result) {
                    is OfflineResult.Say -> lines.add(Line(false, result.text))
                    is OfflineResult.Do -> { lines.add(Line(false, "Working offline: " + result.action.describe())); pendingAction = result.action }
                    OfflineResult.Failed -> lines.add(Line(false, "Sorry, I couldn't work that out without your PC."))
                }
            }
        }
    }

    private fun changeOfflineHelper(on: Boolean) {
        if (!on) { offlineHelperOn = false; prefs.edit().putBoolean("offline_helper", false).apply(); return }
        if (OnDeviceModel.available(appContext)) { offlineHelperOn = true; prefs.edit().putBoolean("offline_helper", true).apply() }
        else offlineDownloadAsk = true
    }

    fun answerOfflineDownload(start: Boolean) {
        offlineDownloadAsk = false
        if (!start || offlineProgress >= 0) return
        offlineProgress = 0
        Thread {
            val problem = OnDeviceModel.download(appContext) { p -> main.post { offlineProgress = p } }
            main.post {
                offlineProgress = -1
                if (problem == null) { offlineHelperOn = true; prefs.edit().putBoolean("offline_helper", true).apply(); showSettingsNote("The offline helper is ready.") }
                else showSettingsNote(problem, error = true)
            }
        }.start()
    }

    /** A question about the settings, asked from the settings screen; its answer is shown there (see [settingsAnswer]). */
    var settingsQuestion by mutableStateOf("")
        private set
    private var settingsQuestionAt = -1

    fun askAboutSettings(question: String) {
        val q = question.trim()
        if (q.isEmpty() || !connected) return
        settingsQuestion = q
        settingsQuestionAt = lines.size
        sendText("A question about your settings: $q")
    }

    fun clearSettingsQuestion() { settingsQuestion = ""; settingsQuestionAt = -1 }

    // ---- things shared from other apps ---------------------------------------------------
    /** What another app just shared, waiting for the user's question. */
    var incomingShare by mutableStateOf<IncomingShare?>(null)
        private set
    /** While a share is on its way: what is happening ("Sending photo.jpg… 40%"), or the problem. */
    var shareStatus by mutableStateOf("")
        private set
    var sharing by mutableStateOf(false)
        private set
    private val shareReplies = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    fun receiveShare(share: IncomingShare) { incomingShare = share; shareStatus = "" }

    fun dismissShare() { if (!sharing) { incomingShare = null; shareStatus = "" } }

    /** Send the shared files to the PC piece by piece, wait until each is attached there, then ask the question. */
    fun sendShare(context: Context, question: String) {
        val share = incomingShare ?: return
        val link = client
        if (link == null || !connected) { shareStatus = "Not connected to your PC."; return }
        sharing = true
        shareStatus = "Sending…"
        Thread {
            val saved = mutableListOf<String>()
            val problems = mutableListOf<String>()
            for ((n, item) in share.items.withIndex()) {
                val id = java.util.UUID.randomUUID().toString().replace("-", "")
                try {
                    val meta = if (shareDetails) MediaMeta.describe(context, item, sharePlaces) else ""
                    val prepared = SharePrep.open(context, item)
                    prepared.stream.use { input ->
                        val buffer = ByteArray(PIECE_BYTES)
                        var seq = 0
                        var sent = 0L
                        while (true) {
                            val read = input.readNBytesCompat(buffer)
                            val last = sent + read >= prepared.size || read < buffer.size
                            var waited = 0
                            while (link.queuedBytes() > 3_000_000 && waited < 600 && client === link) { Thread.sleep(50); waited++ }
                            if (client !== link || waited >= 600) throw IllegalStateException("The link to your PC was lost.")
                            link.shareFile(id, prepared.name, prepared.mime, prepared.size, seq++, last,
                                android.util.Base64.encodeToString(buffer, 0, read, android.util.Base64.NO_WRAP), meta)
                            sent += read
                            val pct = if (prepared.size > 0) (sent * 100 / prepared.size).toInt() else 100
                            main.post { shareStatus = "Sending ${item.kind} ${n + 1} of ${share.items.size}… $pct%" }
                            if (last) break
                        }
                    }
                    var waited = 0
                    while (!shareReplies.containsKey(id) && waited < 1200 && client === link) { Thread.sleep(50); waited++ } // a minute for the PC to finish
                    val reply = shareReplies.remove(id)
                    if (reply?.optBoolean("ok") == true) saved += reply.optString("name") else problems += reply?.optString("message") ?: "${item.name} did not arrive."
                } catch (e: SecurityException) {
                    problems += "The app you shared from did not let this app open ${item.name}. Try saving it first, then share it again."
                } catch (e: java.io.IOException) {
                    problems += "Couldn't read ${item.name} (${e.message})."
                } catch (e: Exception) {
                    problems += e.message ?: "${item.name} could not be sent."
                }
            }
            main.post {
                sharing = false
                if (share.items.isNotEmpty() && saved.isEmpty() && share.text.isBlank()) {
                    shareStatus = problems.joinToString(" ")
                    return@post
                }
                val asked = question.trim().ifBlank { "What can you tell me about this?" }
                shareBubble = (if (share.text.isBlank()) asked else share.text.take(300) + "\n" + asked) to share.items
                sendText(share.describe(saved) + asked)
                incomingShare = null
                shareStatus = if (problems.isEmpty()) "" else problems.joinToString(" ")
            }
        }.start()
    }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private const val PIECE_BYTES = 512 * 1024

    // ---- things the assistant asks the phone to do --------------------------------------------
    /** An action waiting for the user's yes or no (an alarm, a timer, a calendar event). */
    var pendingAction by mutableStateOf<PhoneAction?>(null)
        private set

    private fun receiveAction(action: PhoneAction) {
        if (!action.known) { client?.phoneActionResult(action.id, false, "this phone app does not support '${action.action}' yet"); return }
        if (action.action == "read_notifications") { // the user allowed this once in Settings, so no question each time
            val text = if (readNotifications && NotificationReader.accessGranted(appContext))
                NotificationReader.read(appContext, action.args.optString("app"), action.args.optInt("limit", 10)) else null
            client?.phoneActionResult(action.id, text != null, text ?: "notifications_off")
            return
        }
        pendingAction = action
        if (!action.needsRecipient) WatchLink.ask(action.id, action.describe(), "action")
        if (visibleActivities == 0) { // the screen is off or another app is in front: a notification brings the question up
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("actions", "Requests from your assistant", NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = PendingIntent.getActivity(appContext, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val note = android.app.Notification.Builder(appContext, "actions")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Your assistant is asking")
                .setContentText(action.describe())
                .setContentIntent(tap).setAutoCancel(true).build()
            runCatching { manager.notify(ACTION_NOTIFICATION_ID, note) }
        }
    }

    /** The user's answer. [number] is who they picked for a text or call; [reason] says why not, when they did not agree. */
    fun answerAction(context: Context, ok: Boolean, number: String? = null, reason: String = "declined") {
        val action = pendingAction ?: return
        pendingAction = null
        appContext.getSystemService(NotificationManager::class.java).cancel(ACTION_NOTIFICATION_ID)
        if (action.id == "offline") { // from the offline helper: nobody on the PC is waiting, so the answer goes in the chat
            lines.add(Line(false, if (!ok) "Okay, cancelled." else PhoneAction.perform(context, action, number, direct = directReady).second))
            return
        }
        if (!ok) { client?.phoneActionResult(action.id, false, reason); return }
        val (done, message) = PhoneAction.perform(context, action, number, direct = directReady)
        client?.phoneActionResult(action.id, done, message)
    }

    private const val ACTION_NOTIFICATION_ID = 3

    /** The assistant's reply to [settingsQuestion] so far ("" until it starts answering). */
    val settingsAnswer: String
        get() = if (settingsQuestionAt < 0) "" else lines.drop(settingsQuestionAt).lastOrNull { !it.fromUser }?.text.orEmpty()

    fun answer(ok: Boolean) {
        approval?.let { client?.confirmResult(it.id, ok) }
        approval = null
    }

    // ---- messages from the PC ------------------------------------------------------
    override fun onPaired(message: JSONObject) {
        main.post {
            val request = pendingRequest ?: return@post
            val p = Pairing(request.host, request.port, message.optString("token"), message.optString("name").ifBlank { request.name })
            prefs.edit().putString("pairing", p.toJson()).apply()
            pairing = p
            pcKnowsPhone = true
            wantConnected = true // this very connection carries on as the phone's normal link
            pairBusy = false
            pendingRequest = null
        }
    }

    override fun onRefused(code: String, message: String) {
        main.post {
            pairBusy = false
            pairProblem = ConnectionDoctor.refusal(code)
            if (code == "not_paired") {
                pcKnowsPhone = false
                wantConnected = false // retrying with a token the PC does not know would only fail again
            }
            disconnect()
        }
    }

    override fun onReady(message: JSONObject) {
        main.post {
            pcKnowsPhone = true
            connected = true
            status = "Connected to ${message.optString("name", pairing?.name ?: "your PC")}"
            pushWatchState()
            assistantState = message.optString("state", "idle")
            if (settings != null) client?.getSettings() // the screen was open before the link dropped
            if (handsFree && keywordLines.isBlank()) client?.getKeywords(wakePhrase, thresholds[sensitivity])
            else if (handsFree && engine == null) startHandsFreeWhenReady()
        }
    }

    override fun onMessage(message: JSONObject) {
        main.post {
            when (message.optString("type")) {
                "state" -> {
                    assistantState = message.optString("state")
                    engine?.onPcState(assistantState)
                    pushWatchState()
                    if (watchTalking && assistantState == "listening") flushWatchAudio()
                    if (assistantState == "idle" || assistantState == "speaking") sent = false
                    if (assistantState == "listening" && talking && waitingForListening) main.postDelayed({ if (talking) startMic() }, 100)
                }
                "partial" -> {
                    partial = message.optString("text")
                    if (partial.isNotBlank()) gotWords = true
                }
                "user_final" -> {
                    partial = ""
                    gotWords = true
                    sent = false
                    main.removeCallbacks(noWordsCheck)
                    val shown = shareBubble
                    shareBubble = null
                    lines.add(if (shown != null) Line(true, shown.first, media = shown.second) else Line(true, message.optString("text")))
                    WatchLink.heard(message.optString("text"))
                    lines.add(Line(false, ""))
                }
                "keywords" -> {
                    keywordLines = message.optString("lines")
                    prefs.edit().putString("kw_lines", keywordLines).putString("kw_key", keywordKey()).apply()
                    hfNotice = ""
                    val d = detectors
                    if (d != null) d.setKeywords(keywordLines) else if (handsFree) startHandsFreeWhenReady()
                }
                "keywords_error" -> {
                    handsFree = false
                    prefs.edit().putBoolean("handsfree", false).apply()
                    hfNotice = "That wake phrase won't work (${message.optString("message")}). Try plain words, like “Hey Jarvis”."
                }
                "settings" -> settings = SettingsSnapshot.parse(message)
                "setting_result" -> {
                    val key = message.optString("key")
                    val label = settings?.find(key)?.label ?: "Setting"
                    val back = previousValues.remove(key)
                    if (message.optBoolean("ok")) {
                        settings?.let { current ->
                            val kind = current.find(key)?.kind
                            val saved: Any? = when (kind) {
                                "toggle" -> message.optBoolean("value")
                                "number" -> message.optDouble("value")
                                else -> message.optString("value")
                            }
                            settings = current.withValue(key, saved).withRestartPending(message.optBoolean("restart"))
                        }
                        settingsNote = SettingsNote("$label: ${message.optString("message", "Saved.")}", undoKey = key, undoValue = back)
                    } else {
                        settings?.let { current ->
                            val kind = current.find(key)?.kind
                            val actual: Any? = when (kind) {
                                "toggle" -> message.optBoolean("value")
                                "number" -> message.optDouble("value")
                                else -> message.optString("value")
                            }
                            settings = current.withValue(key, actual) // put back what the PC still has
                        }
                        settingsNote = SettingsNote(message.optString("message", "That did not work."), error = true)
                    }
                }
                "camera_request" -> openCamera(
                    CameraRequest(
                        message.optString("id"), message.optString("prompt"), message.optBoolean("auto"),
                        mode = message.optString("mode", "photo"),
                        maxSeconds = message.optInt("max_seconds", 15).coerceIn(3, 120),
                        maxFrames = message.optInt("max_frames", 100).coerceIn(2, 500),
                        maxEdge = message.optInt("max_edge", 640).coerceIn(320, 1920),
                        maxFps = message.optDouble("max_fps", 2.0).toFloat().coerceIn(0.1f, 10f),
                        maxBytes = message.optLong("max_bytes", 5_000_000L).coerceIn(500_000L, 65_000_000L),
                        audio = message.optBoolean("audio", false),
                        detailFrames = message.optInt("detail_frames", 0).coerceIn(0, 8),
                        detailEdge = message.optInt("detail_edge", 1280).coerceIn(640, 1920),
                    ),
                )
                "camera_close" -> if (camera?.id == message.optString("id")) closeCamera()
                "share_saved" -> shareReplies[message.optString("id")] = message
                "chats" -> {
                    val items = message.optJSONArray("items")
                    chats = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                        items?.optJSONObject(i)?.let { ChatSummary(it.optString("id"), it.optString("title"), it.optLong("updated"), it.optInt("count")) }
                    }
                    currentChatId = message.optString("current")
                    chatsLoading = false
                }
                "chat_opened" -> if (message.optBoolean("ok")) {
                    lines.clear()
                    shareBubble = null
                    val messages = message.optJSONArray("messages")
                    for (i in 0 until (messages?.length() ?: 0)) {
                        val m = messages?.optJSONObject(i) ?: continue
                        lines.add(Line(m.optString("role") == "user", m.optString("text")))
                    }
                    currentChatId = message.optString("id")
                    notice = ""
                } else notice = "That chat could not be opened."
                "card" -> attachCard(AnswerCard.parse(message))
                "phone_action" -> receiveAction(PhoneAction(message.optString("id"), message.optString("action"), message.optJSONObject("args") ?: JSONObject()))
                "assistant_delta" -> {
                    appendAssistant(message.optString("text"))
                    WatchLink.reply(lines.lastOrNull { !it.fromUser }?.text.orEmpty())
                }
                "confirm" -> {
                    approval = Approval(message.optString("id"), message.optString("prompt"))
                    WatchLink.ask(message.optString("id"), message.optString("prompt"), "approval")
                }
                "audio_start" -> {
                    if (talkEndedAt != 0L) {
                        latency = "Reply started ${"%.1f".format((SystemClock.elapsedRealtime() - talkEndedAt) / 1000.0)} s after you let go"
                        talkEndedAt = 0L
                    }
                    player.start(message.optInt("sr", 16000))
                }
                "audio_flush" -> player.flush()
                "error" -> lines.add(Line(false, "⚠ " + message.optString("message")))
            }
        }
    }

    // ---- camera ----------------------------------------------------------------------
    fun activityStarted() { visibleActivities++ }
    fun activityStopped() { visibleActivities = maxOf(0, visibleActivities - 1) }

    /** The assistant wants a photo. Android only lets an app open its camera by itself while the app is on screen; otherwise show a notification to tap. */
    private fun openCamera(request: CameraRequest) {
        camera = request
        val open = Intent(appContext, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (visibleActivities > 0) {
            appContext.startActivity(open)
            client?.cameraPending(request.id, true)
        } else {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("camera", "Camera requests", NotificationManager.IMPORTANCE_HIGH))
            val tap = PendingIntent.getActivity(appContext, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val note = android.app.Notification.Builder(appContext, "camera")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(if (request.isVideo) "Tap to record the video" else "Tap to take the photo")
                .setContentText(request.prompt.ifBlank { "Your assistant wants to see something." })
                .setContentIntent(tap).setAutoCancel(true).build()
            runCatching { manager.notify(CAMERA_NOTIFICATION_ID, note) }
            client?.cameraPending(request.id, false)
        }
    }

    private fun closeCamera() {
        camera = null
        appContext.getSystemService(NotificationManager::class.java).cancel(CAMERA_NOTIFICATION_ID)
    }

    /** The user closed the camera, or it could not open. */
    fun cancelCamera(reason: String) {
        camera?.let { client?.cameraCancel(it.id, reason) }
        closeCamera()
    }

    /** The prepared JPEG from the camera screen. */
    fun sendPhoto(jpeg: ByteArray) {
        val request = camera ?: return
        client?.cameraPhoto(request.id, android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
        main.post { closeCamera() }
    }

    /** The frames picked from a recorded clip. They go one message at a time, waiting whenever the link is busy, so a long high-quality clip never overflows its queue. */
    fun sendVideo(requestId: String, frames: List<SampledFrame>, durationMs: Long, audio: ByteArray? = null, detail: List<SampledFrame> = emptyList()) {
        val link = client ?: return
        Thread {
            link.cameraVideoBegin(requestId, frames.size, durationMs, detail.size)
            for ((i, f) in frames.withIndex()) {
                var waited = 0
                while (link.queuedBytes() > 2_000_000 && waited < 600 && client === link) { Thread.sleep(50); waited++ } // up to 30 s per frame
                if (client !== link || waited >= 600) { main.post { cancelCamera("The link to your PC was lost while sending the video.") }; return@Thread }
                link.cameraVideoFrame(requestId, i, f.timeMs, android.util.Base64.encodeToString(f.jpeg, android.util.Base64.NO_WRAP))
            }
            // a few extra frames at full detail, for reading small text and seeing what is held
            for ((i, f) in detail.withIndex()) {
                var waited = 0
                while (link.queuedBytes() > 2_000_000 && waited < 600 && client === link) { Thread.sleep(50); waited++ }
                if (client !== link || waited >= 600) { main.post { cancelCamera("The link to your PC was lost while sending the close-ups.") }; return@Thread }
                link.cameraVideoFrame(requestId, i, f.timeMs, android.util.Base64.encodeToString(f.jpeg, android.util.Base64.NO_WRAP), detail = true)
            }
            // the sound, if it was asked for, in pieces of about 400 KB (a little over 12 seconds each)
            audio?.let { pcm ->
                var offset = 0
                while (offset < pcm.size) {
                    var waited = 0
                    while (link.queuedBytes() > 2_000_000 && waited < 600 && client === link) { Thread.sleep(50); waited++ }
                    if (client !== link || waited >= 600) { main.post { cancelCamera("The link to your PC was lost while sending the sound.") }; return@Thread }
                    val end = minOf(pcm.size, offset + 400_000)
                    link.cameraVideoAudio(requestId, android.util.Base64.encodeToString(pcm.copyOfRange(offset, end), android.util.Base64.NO_WRAP))
                    offset = end
                }
            }
            link.cameraVideoEnd(requestId)
            main.post { closeCamera() }
        }.start()
    }

    private const val CAMERA_NOTIFICATION_ID = 2

    /** A card goes under the reply it belongs to: the one already holding it (the pictures arrive as an update), else the latest reply. */
    private fun attachCard(card: AnswerCard) {
        if (card.isEmpty) return
        val i = lines.indexOfLast { it.card?.id == card.id }.takeIf { it >= 0 } ?: lines.indexOfLast { !it.fromUser && it.text.isNotBlank() }
        if (i >= 0) lines[i] = lines[i].copy(card = card)
    }

    private fun appendAssistant(text: String) {
        val last = lines.lastIndex
        if (last >= 0 && !lines[last].fromUser && lines[last].card == null) lines[last] = lines[last].copy(text = lines[last].text + text) else lines.add(Line(false, text))
    }

    override fun onAudio(pcm: ByteArray) = player.write(pcm)

    override fun onClosed(reason: String) {
        main.post {
            if (pairBusy) {
                pairBusy = false
                if (pairProblem.isBlank()) pairProblem = "Couldn't reach your PC. Check that Tailscale is on for both devices, the PC is awake, and the phone link is turned on there."
            }
            disconnect()
            if (wantConnected) {
                status = "Can't reach your PC, retrying…"
                main.postDelayed({ if (wantConnected && client == null) connect() }, 3000)
            }
        }
    }
}
