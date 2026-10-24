package com.voiceassistant.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.voiceassistant.phone.ui.theme.VoiceAssistantPhoneTheme
import java.util.concurrent.Executors

/**
 * The camera the assistant opens when you ask "what am I looking at?": frame it, zoom, tap the shutter. The photo goes to the PC, which
 * describes it; the spoken answer plays on the main screen. The PC can also close it again (you said the wake phrase, or pressed Stop).
 */
class CameraActivity : ComponentActivity() {
    private companion object {
        val LENSES = listOf(0.5f, 1f, 2f, 5f)
        val MARKS = listOf(0.5f, 1f, 2f, 5f, 10f, 30f)
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private var cameraStarted = false
    private val worker = Executors.newSingleThreadExecutor()

    private var zoom by mutableFloatStateOf(1f)
    private var minZoom by mutableFloatStateOf(1f)
    private var maxZoom by mutableFloatStateOf(1f)
    private var countdown by mutableIntStateOf(0)
    private var timerOn by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var countdownJob: Runnable? = null

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Controller.cancelCamera("The camera permission was not allowed on the phone.")
            Toast.makeText(this, "Allow the camera in the phone's app settings to use this.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Controller.init(this)
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("demo", false) && Controller.camera == null) Controller.openDemoCamera()
        timerOn = Controller.cameraTimerDefault
        if (Controller.camera == null) { finish(); return }
        setContent {
            VoiceAssistantPhoneTheme {
                // the PC closes the camera too (the wake phrase or Stop was used)
                LaunchedEffect(Controller.camera) { if (Controller.camera == null) finish() }
                CameraScreen()
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() { super.onStart(); Controller.activityStarted() }
    override fun onStop() { super.onStop(); Controller.activityStopped() }

    override fun onDestroy() {
        countdownJob?.let { window.decorView.removeCallbacks(it) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val view = previewView ?: return
        if (cameraStarted || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            provider.unbindAll()
            val bound = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            camera = bound
            imageCapture = capture
            bound.cameraInfo.zoomState.observe(this) { state ->
                zoom = state.zoomRatio
                minZoom = state.minZoomRatio
                maxZoom = state.maxZoomRatio
                android.util.Log.i("VoiceAssistant", "camerax zoom range ${state.minZoomRatio}..${state.maxZoomRatio} now ${state.zoomRatio}")
            }
            logLenses()
            if (Controller.camera?.auto == true) startCountdown()
        }, ContextCompat.getMainExecutor(this))
    }

    /** What the phone's cameras offer, written to the log for debugging. */
    private fun logLenses() {
        runCatching {
            val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue
                val zoomRange = c.get(android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                val focal = c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                val sensor = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                android.util.Log.i("VoiceAssistant", "camera2 id=$id zoomRange=$zoomRange focal=$focal sensor=$sensor physical=${c.physicalCameraIds}")
                for (p in c.physicalCameraIds) {
                    val pc = manager.getCameraCharacteristics(p)
                    android.util.Log.i("VoiceAssistant", "  physical $p zoomRange=${pc.get(android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)} " +
                        "focal=${pc.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()}")
                }
            }
        }.onFailure { android.util.Log.w("VoiceAssistant", "could not read lenses", it) }
    }

    private fun zoomTo(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    private fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun startCountdown() {
        if (busy || countdown > 0) return
        countdown = 3
        val tick = object : Runnable {
            override fun run() {
                if (countdown <= 1) { countdown = 0; capture() } else { countdown -= 1; window.decorView.postDelayed(this, 1000) }
            }
        }
        countdownJob = tick
        window.decorView.postDelayed(tick, 1000)
    }

    private fun capture() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                Controller.sendPhoto(PhotoPrep.prepare(bytes, rotation))
                runOnUiThread { finish() }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    busy = false
                    Toast.makeText(this@CameraActivity, "The photo did not work. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** A zoom slider that moves evenly through 0.5×, 1×, 2×, 5×... (zoom is multiplicative, so the scale is logarithmic), with the lens marks under it. */
    @androidx.compose.runtime.Composable
    private fun ZoomSlider() {
        val span = kotlin.math.ln(maxZoom / minZoom)
        val position = (kotlin.math.ln(zoom.coerceIn(minZoom, maxZoom) / minZoom) / span).coerceIn(0f, 1f)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            androidx.compose.material3.Slider(
                value = position,
                onValueChange = { t -> zoomTo(minZoom * kotlin.math.exp(t * span)) },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
            )
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                MARKS.filter { it >= minZoom - 0.02f && it <= maxZoom }.forEach { mark ->
                    val fraction = (kotlin.math.ln(mark.coerceAtLeast(minZoom) / minZoom) / span).coerceIn(0f, 1f)
                    Text(
                        if (mark < 1f) "%.1f".format(mark) else mark.toInt().toString(),
                        Modifier.padding(start = (maxWidth * fraction - 8.dp).coerceIn(0.dp, maxWidth - 22.dp)), color = Color.White, fontSize = 12.sp, softWrap = false,
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun CameraScreen() {
        val request = Controller.camera
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also {
                        it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        previewView = it
                        startCamera()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // pinch to zoom, tap to focus
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTransformGestures { _, _, gestureZoom, _ -> if (gestureZoom != 1f) zoomTo(zoom * gestureZoom) } }
                    .pointerInput(Unit) { detectTapGestures { offset -> focusAt(offset.x, offset.y) } },
            )
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton({ Controller.cancelCamera("cancelled"); finish() }) { Text("✕ Close", color = Color.White) }
                Text(
                    "%.1f×".format(zoom),
                    color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.clip(CircleShape).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (countdown > 0) {
                Text(countdown.toString(), Modifier.align(Alignment.Center), color = Color.White, fontSize = 96.sp)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val ask = request?.prompt.orEmpty()
                Text(
                    if (ask.isBlank()) "Point at what you want to know about" else "“$ask”",
                    color = Color.White, modifier = Modifier.padding(horizontal = 24.dp),
                )
                // the lenses a Pixel has: ultrawide 0.5×, main 1×, then the zoom lenses; the slider below slides smoothly between all of them
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LENSES.filter { it >= minZoom - 0.02f && it <= maxZoom }.forEach { step ->
                        val selected = kotlin.math.abs(kotlin.math.ln(zoom / step)) < 0.12f
                        val label = if (step < 1f) "%.1f×".format(step) else "${step.toInt()}×"
                        if (selected) Button({ zoomTo(step) }) { Text(label) } else OutlinedButton({ zoomTo(step) }) { Text(label, color = Color.White) }
                    }
                }
                if (maxZoom > minZoom + 0.01f) ZoomSlider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        OutlinedButton({ timerOn = !timerOn }) { Text(if (timerOn) "3 s: on" else "3 s timer", color = Color.White) }
                    }
                    Box(
                        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape)
                            .background(if (busy) Color.Gray else Color.White)
                            .pointerInput(busy) { detectTapGestures { if (!busy) { if (timerOn) startCountdown() else capture() } } },
                    )
                    Box(Modifier.weight(1f))
                }
                if (busy) Text("Sent. Looking at it…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
