package org.nacre.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.camera.core.ZoomState
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.MutableLiveData
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Launch(
    val start: Mode = Mode.Photo,
    val modes: List<Mode> = Mode.entries,
    val capture: Boolean = false,
    val output: Uri? = null,
    val secure: Boolean = false,
    val durationLimit: Long = 0,
    val sizeLimit: Long = 0
)

class Orientation {
    var degrees by mutableIntStateOf(0)
    var tilt by mutableStateOf(0f)
}

private fun <T> List<T>.after(item: T) = this[(indexOf(item) + 1) % size]

private fun ComponentActivity.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun duration(nanos: Long): String {
    val seconds = nanos / 1_000_000_000L
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun CameraApp(
    activity: ComponentActivity,
    camera: Camera,
    launch: Launch,
    mode: MutableState<Mode>,
    orientation: Orientation,
    shutter: Flow<Unit>,
    onPhoto: (File) -> Unit,
    onVideo: (Uri) -> Unit
) {
    var cameraAllowed by remember { mutableStateOf(activity.granted(Manifest.permission.CAMERA)) }
    val request = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        cameraAllowed = activity.granted(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!cameraAllowed) request.launch(arrayOf(Manifest.permission.CAMERA))
    }

    if (!cameraAllowed) {
        return PermissionScreen {
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                request.launch(arrayOf(Manifest.permission.CAMERA))
            } else {
                val uri = Uri.fromParts("package", activity.packageName, null)
                activity.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
        }
    }
    Viewfinder(activity, camera, launch, mode, orientation, shutter, onPhoto, onVideo) {
        request.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(48.dp))
        Text("Camera access is needed to take photos and videos.")
        Button(onAllow) { Text("Allow camera") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewfinder(
    activity: ComponentActivity,
    camera: Camera,
    launch: Launch,
    modeState: MutableState<Mode>,
    orientation: Orientation,
    shutter: Flow<Unit>,
    onPhoto: (File) -> Unit,
    onVideo: (Uri) -> Unit,
    requestMicrophone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Store.settings.collectAsState()
    val mode = modeState.value
    val capabilities = camera.capabilities
    val recording = camera.recording != null
    val angle = -orientation.degrees.toFloat()

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var ready by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var histogram by remember { mutableStateOf<IntArray?>(null) }
    var manual by remember { mutableStateOf(Manual()) }
    var ev by remember { mutableIntStateOf(0) }
    var exposureShown by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var timerJob by remember { mutableStateOf<Job?>(null) }
    var torchSlider by remember { mutableStateOf(false) }
    var torchLevel by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<File?>(null) }
    var latest by remember { mutableStateOf<Media?>(null) }
    var galleryItems by remember { mutableStateOf(emptyList<Media>()) }
    val session = remember { mutableStateListOf<Media>() }
    val flash = remember { Animatable(0f) }
    val emptyZoom = remember { MutableLiveData<ZoomState>() }
    val zoom by (camera.bound?.cameraInfo?.zoomState ?: emptyZoom).observeAsState()

    LaunchedEffect(Unit) {
        camera.open()
        ready = true
    }
    LaunchedEffect(
        ready, mode, settings.front, settings.ratio, settings.effect, settings.ultraHdr,
        settings.raw, settings.resolution, settings.smooth, settings.stabilization,
        settings.hdrVideo
    ) {
        if (!ready) return@LaunchedEffect
        val main = context.mainExecutor
        val analyzer = when (mode) {
            Mode.Scan -> Scanner { text -> main.execute { if (scanned == null) scanned = text } }
            Mode.Pro -> Histogram { bins -> main.execute { histogram = bins } }
            else -> null
        }
        camera.bind(mode, settings, previewView.surfaceProvider, analyzer)
    }
    LaunchedEffect(settings.front) { manual = Manual() }
    LaunchedEffect(camera.bound, manual, mode) {
        ev = 0
        camera.apply(if (mode == Mode.Pro) manual else Manual())
    }
    LaunchedEffect(mode) {
        scanned = null
        val microphone = Manifest.permission.RECORD_AUDIO
        if (mode == Mode.Video && settings.audio && !activity.granted(microphone)) {
            requestMicrophone()
        }
    }
    LaunchedEffect(focusPoint) {
        delay(1200)
        focusPoint = null
    }
    LaunchedEffect(exposureShown, ev) {
        delay(4000)
        exposureShown = false
    }
    LaunchedEffect(gallery, session.size) {
        if (gallery || launch.capture) return@LaunchedEffect
        latest = if (launch.secure) {
            session.firstOrNull()
        } else {
            withContext(Dispatchers.IO) { context.ownMedia().firstOrNull() }
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun takePhoto() {
        scope.launch {
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(300))
        }
        val file = if (launch.capture) File(context.cacheDir, "capture.jpg") else null
        camera.takePhoto(settings, file) { uri ->
            when {
                uri == null -> toast("Couldn't save photo")
                file != null -> review = file
                else -> session.add(0, Media(uri, false))
            }
        }
    }

    fun startVideo() {
        val target = launch.output?.let { context.contentResolver.openFileDescriptor(it, "rw") }
        camera.record(settings.audio, target, launch.durationLimit, launch.sizeLimit) { uri ->
            when {
                uri == null -> toast("Couldn't save video")
                launch.capture -> onVideo(uri)
                else -> session.add(0, Media(uri, true))
            }
        }
    }

    fun act() = if (mode == Mode.Video) startVideo() else takePhoto()

    fun press() {
        if (timerJob != null) {
            timerJob?.cancel()
            timerJob = null
            countdown = 0
            return
        }
        if (recording) return camera.stopRecording().let { }
        val seconds = settings.timer
        if (seconds == 0) return act()
        timerJob = scope.launch {
            for (left in seconds downTo 1) {
                countdown = left
                delay(1000)
            }
            countdown = 0
            timerJob = null
            act()
        }
    }

    val onShutter by rememberUpdatedState {
        if (!gallery && review == null && mode != Mode.Scan) press()
    }
    LaunchedEffect(Unit) { shutter.collect { onShutter() } }

    val busy = recording || countdown > 0
    val ratio = if (mode == Mode.Video) Ratio.Wide else settings.ratio

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(camera, mode, settings, busy, angle, onTorchLevels = {
                torchSlider = !torchSlider
            }) { sheet = true }

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio.portrait)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            ) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(mode, manual.focus) {
                            detectTapGestures { offset ->
                                if (mode == Mode.Scan || manual.focus != null) return@detectTapGestures
                                val factory = previewView.meteringPointFactory
                                camera.focus(factory.createPoint(offset.x, offset.y))
                                focusPoint = offset
                                exposureShown = mode != Mode.Pro
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, change, _ ->
                                val current = camera.bound?.cameraInfo?.zoomState?.value
                                current?.let { camera.zoom(it.zoomRatio * change) }
                            }
                        }
                        .pointerInput(busy, launch.modes) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    val modes = launch.modes
                                    val index = modes.indexOf(modeState.value)
                                    val step = if (total < -120) 1 else if (total > 120) -1 else 0
                                    val next = modes.getOrNull(index + step)
                                    if (!busy && step != 0 && next != null) modeState.value = next
                                }
                            ) { _, amount -> total += amount }
                        }
                )
                if (settings.grid) Grid()
                if (settings.level) Level(orientation.tilt)
                focusPoint?.let { FocusRing(it) }
                if (mode == Mode.Pro) {
                    histogram?.let { HistogramView(it, Modifier.padding(12.dp)) }
                }
                if (mode == Mode.Scan) ScanFrame()
                if (countdown > 0) {
                    Text(
                        "$countdown",
                        Modifier.align(Alignment.Center).rotate(angle),
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Light
                    )
                }
                if (recording) RecordingBadge(camera, Modifier.align(Alignment.TopCenter))
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = flash.value)))
                scanned?.let {
                    Box(Modifier.align(Alignment.BottomCenter)) { ScanResult(it) { scanned = null } }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (torchSlider && camera.torch && capabilities.torchLevels > 1) {
                val levels = (1..capabilities.torchLevels).toList()
                val level = torchLevel.takeIf { it in levels } ?: levels.last()
                StepSlider(levels.map { "Brightness $it" }, levels.indexOf(level)) {
                    torchLevel = levels[it]
                    camera.torchLevel(levels[it])
                }
            }
            val exposureState = camera.bound?.cameraInfo?.exposureState
            val exposure = exposureState?.takeIf { it.isExposureCompensationSupported }?.let {
                Exposure(ev, it.exposureCompensationRange.lower..it.exposureCompensationRange.upper, it.exposureCompensationStep.toFloat())
            }
            if (exposureShown && exposure != null && mode != Mode.Pro && mode != Mode.Scan) {
                val range = exposure.range.toList()
                StepSlider(range.map { "Brightness %+.1f".format(it * exposure.step) }, range.indexOf(ev)) {
                    ev = range[it]
                    camera.exposure(ev)
                }
            }
            if (mode == Mode.Pro) {
                ProControls(capabilities, manual, { manual = it }, exposure) {
                    ev = it
                    camera.exposure(it)
                }
            }
            zoom?.let { if (mode != Mode.Scan) ZoomRow(it, angle, camera::zoom) }
            if (launch.modes.size > 1 && !busy) {
                ModeRow(launch.modes, mode) { modeState.value = it }
            }
            ShutterRow(
                mode = mode,
                recording = recording,
                paused = camera.paused,
                counting = countdown > 0,
                angle = angle,
                thumbnail = latest.takeIf { !launch.capture },
                switchable = capabilities.switchable,
                onGallery = {
                    galleryItems = if (launch.secure) session.toList() else context.ownMedia()
                    gallery = true
                },
                onShutter = ::press,
                onPause = camera::togglePause,
                onFlip = { Store.update { copy(front = !front) } }
            )
        }

        if (sheet) SettingsSheet(capabilities) { sheet = false }
        if (gallery) {
            Gallery(
                galleryItems,
                launch.secure,
                onDelete = { media ->
                    session.remove(media)
                    galleryItems = galleryItems - media
                },
                onClose = { gallery = false }
            )
        }
        review?.let { file ->
            Review(file, onRetake = { review = null }, onUse = { onPhoto(file) })
        }
    }
}

@Composable
private fun TopBar(
    camera: Camera,
    mode: Mode,
    settings: Settings,
    busy: Boolean,
    angle: Float,
    onTorchLevels: () -> Unit,
    onSettings: () -> Unit
) {
    val capabilities = camera.capabilities
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!busy) {
            when (mode) {
                Mode.Photo -> {
                    if (capabilities.flash) {
                        Control(settings.flash.label, angle, R.drawable.ic_flash, settings.flash != Flash.Off) {
                            Store.update { copy(flash = Flash.entries.after(flash)) }
                        }
                    }
                    TimerControl(settings, angle)
                    RatioControl(settings, angle)
                    if (capabilities.effects.size > 1) {
                        val effect = settings.effect.takeIf { it in capabilities.effects } ?: Effect.None
                        Control(effect.label, angle, R.drawable.ic_camera, effect != Effect.None) {
                            Store.update { copy(effect = capabilities.effects.after(effect)) }
                        }
                    }
                }
                Mode.Video -> {
                    if (capabilities.resolutions.isNotEmpty()) {
                        val current = settings.resolution.takeIf { it in capabilities.resolutions }
                            ?: capabilities.resolutions.last()
                        Control(current.label, angle, R.drawable.ic_video) {
                            Store.update { copy(resolution = capabilities.resolutions.after(current)) }
                        }
                    }
                    if (capabilities.smooth) {
                        Control(if (settings.smooth) "60 fps" else "30 fps", angle, R.drawable.ic_play, settings.smooth) {
                            Store.update { copy(smooth = !smooth) }
                        }
                    }
                    Control(if (settings.audio) "Sound" else "Muted", angle, R.drawable.ic_mic, settings.audio) {
                        Store.update { copy(audio = !audio) }
                    }
                }
                Mode.Pro -> {
                    TimerControl(settings, angle)
                    RatioControl(settings, angle)
                    if (capabilities.raw) {
                        Control(if (settings.raw) "RAW" else "JPEG", angle, R.drawable.ic_camera, settings.raw) {
                            Store.update { copy(raw = !raw) }
                        }
                    }
                }
                Mode.Scan -> Unit
            }
        }
        if (capabilities.flash) {
            Control("Torch", angle, R.drawable.ic_torch, camera.torch, onLongClick = onTorchLevels) {
                camera.torch(!camera.torch)
            }
        }
        if (!busy) Control("Settings", angle, R.drawable.ic_settings, onClick = onSettings)
    }
}

@Composable
private fun TimerControl(settings: Settings, angle: Float) {
    val label = if (settings.timer == 0) "Timer" else "${settings.timer}s"
    Control(label, angle, R.drawable.ic_timer, settings.timer != 0) {
        Store.update { copy(timer = Settings.TIMERS.after(timer)) }
    }
}

@Composable
private fun RatioControl(settings: Settings, angle: Float) {
    Control(settings.ratio.label, angle, R.drawable.ic_ratio) {
        Store.update { copy(ratio = Ratio.entries.after(ratio)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Control(
    label: String,
    angle: Float,
    icon: Int,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) colors.surfaceVariant else Color.Transparent)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .rotate(angle),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(icon), label, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Grid() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 2f)
            drawLine(color, Offset(0f, y), Offset(size.width, y), 2f)
        }
    }
}

@Composable
private fun Level(tilt: Float) {
    if (abs(tilt) > 30) return
    val level = abs(tilt) < 1f
    Canvas(Modifier.fillMaxSize().rotate(if (level) 0f else -tilt)) {
        val half = size.width / 4
        val center = Offset(size.width / 2, size.height / 2)
        drawLine(
            Color.White.copy(alpha = if (level) 1f else 0.6f),
            center - Offset(half, 0f),
            center + Offset(half, 0f),
            if (level) 6f else 3f
        )
    }
}

@Composable
private fun FocusRing(point: Offset) {
    Canvas(Modifier.offset { IntOffset(point.x.roundToInt() - 90, point.y.roundToInt() - 90) }.size(60.dp)) {
        drawCircle(Color.White, size.minDimension / 2, style = Stroke(4f))
    }
}

@Composable
private fun ScanFrame() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(240.dp)
                .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(28.dp))
        )
    }
}

@Composable
private fun RecordingBadge(camera: Camera, modifier: Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
        val text = duration(camera.recorded) + if (camera.paused) "  Paused" else ""
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ZoomRow(state: ZoomState, angle: Float, onZoom: (Float) -> Unit) {
    val presets = listOf(state.minZoomRatio, 1f, 2f, 5f, 10f)
        .filter { it >= state.minZoomRatio && it <= state.maxZoomRatio }
        .distinct()
    if (presets.size < 2) return
    val active = presets.last { it <= state.zoomRatio + 0.01f }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        presets.forEach { preset ->
            val selected = preset == active
            val value = if (selected) state.zoomRatio else preset
            val label = "%.1f".format(value).removeSuffix(".0") + "×"
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surfaceVariant)
                    .combinedClickable { onZoom(preset) }
                    .rotate(angle),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun ModeRow(modes: List<Mode>, mode: Mode, onMode: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        modes.forEach {
            val selected = it == mode
            Text(
                it.name,
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .combinedClickable { onMode(it) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ShutterRow(
    mode: Mode,
    recording: Boolean,
    paused: Boolean,
    counting: Boolean,
    angle: Float,
    thumbnail: Media?,
    switchable: Boolean,
    onGallery: () -> Unit,
    onShutter: () -> Unit,
    onPause: () -> Unit,
    onFlip: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(56.dp)) {
            val bitmap = rememberThumbnail(thumbnail?.uri, 160)
            if (thumbnail != null && !recording) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .combinedClickable(onClick = onGallery)
                ) {
                    bitmap?.let {
                        Image(it, "Gallery", Modifier.fillMaxSize().rotate(angle), contentScale = ContentScale.Crop)
                    }
                }
            }
        }

        Box(
            Modifier
                .size(80.dp)
                .border(4.dp, colors.onBackground, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (mode == Mode.Scan) Color.Transparent else colors.onBackground)
                .combinedClickable(enabled = mode != Mode.Scan, onClick = onShutter),
            contentAlignment = Alignment.Center
        ) {
            val icon = when {
                counting -> R.drawable.ic_close
                recording -> R.drawable.ic_stop
                mode == Mode.Video -> R.drawable.ic_video
                mode == Mode.Scan -> R.drawable.ic_scan
                else -> null
            }
            icon?.let {
                val tint = if (mode == Mode.Scan) colors.onBackground else colors.background
                Icon(painterResource(it), null, Modifier.size(28.dp), tint = tint)
            }
        }

        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            val icon = when {
                recording -> if (paused) R.drawable.ic_play else R.drawable.ic_pause
                switchable && !counting -> R.drawable.ic_flip
                else -> null
            }
            icon?.let {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .combinedClickable(onClick = if (recording) onPause else onFlip)
                        .rotate(angle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(it), if (recording) "Pause" else "Switch camera")
                }
            }
        }
    }
}

@Composable
private fun Review(file: File, onRetake: () -> Unit, onUse: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.setTargetSampleSize(2) }
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onRetake, Modifier.weight(1f)) { Text("Retake") }
            Button(onUse, Modifier.weight(1f)) { Text("Use photo") }
        }
        Spacer(Modifier.height(0.dp))
    }
}
