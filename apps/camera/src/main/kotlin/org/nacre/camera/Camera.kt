package org.nacre.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Mode { Photo, Video, Pro, Scan }

enum class Balance(val label: String, val mode: Int) {
    Auto("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    Daylight("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    Cloudy("Cloudy", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    Shade("Shade", CaptureRequest.CONTROL_AWB_MODE_SHADE),
    Tungsten("Tungsten", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    Fluorescent("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
}

data class Manual(
    val iso: Int? = null,
    val shutter: Long? = null,
    val focus: Float? = null,
    val balance: Balance = Balance.Auto
) {
    val exposure get() = iso != null || shutter != null
}

data class Capabilities(
    val switchable: Boolean = false,
    val flash: Boolean = false,
    val torchLevels: Int = 0,
    val effects: List<Effect> = listOf(Effect.None),
    val ultraHdr: Boolean = false,
    val raw: Boolean = false,
    val resolutions: List<Resolution> = emptyList(),
    val smooth: Boolean = false,
    val stabilization: Boolean = false,
    val hdrVideo: Boolean = false,
    val isoSteps: List<Int> = emptyList(),
    val shutterSteps: List<Long> = emptyList(),
    val minFocus: Float = 0f
)

private val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
private val SHUTTER_STEPS = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
    .map { 1_000_000_000L / it } + 1_000_000_000L
private val FINISHED = setOf(
    VideoRecordEvent.Finalize.ERROR_NONE,
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
)

class Camera(private val activity: ComponentActivity) {
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var rotation = Surface.ROTATION_0
    private val main = activity.mainExecutor
    private val worker = Executors.newSingleThreadExecutor()
    private val sound = MediaActionSound()

    var bound by mutableStateOf<androidx.camera.core.Camera?>(null)
        private set
    var capabilities by mutableStateOf(Capabilities())
        private set
    var recording by mutableStateOf<Recording?>(null)
        private set
    var recorded by mutableLongStateOf(0L)
        private set
    var paused by mutableStateOf(false)
        private set
    var torch by mutableStateOf(false)
        private set

    suspend fun open() {
        val provider = ProcessCameraProvider.awaitInstance(activity)
        extensions = runCatching { ExtensionsManager.getInstance(activity, provider) }.getOrNull()
        this.provider = provider
    }

    fun close() {
        recording?.stop()
        provider?.unbindAll()
        sound.release()
        worker.shutdown()
    }

    fun bind(mode: Mode, settings: Settings, preview: Preview.SurfaceProvider, analyzer: ImageAnalysis.Analyzer?) {
        val provider = provider ?: return
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val hasBack = provider.hasCamera(back)
        val hasFront = provider.hasCamera(front)
        if (!hasBack && !hasFront) return
        val selector = if (settings.front && hasFront || !hasBack) front else back
        capabilities = capabilitiesOf(provider.getCameraInfo(selector), selector, hasBack && hasFront)

        provider.unbindAll()
        bound = runCatching {
            val useCases = useCases(mode, settings, preview, analyzer, safe = false)
            provider.bindToLifecycle(activity, selectorFor(mode, settings, selector), *useCases)
        }.recoverCatching {
            provider.unbindAll()
            val useCases = useCases(mode, settings, preview, analyzer, safe = true)
            provider.bindToLifecycle(activity, selector, *useCases)
        }.getOrNull()
        if (torch) bound?.cameraControl?.enableTorch(true)
    }

    private fun selectorFor(mode: Mode, settings: Settings, selector: CameraSelector): CameraSelector {
        val extensions = extensions ?: return selector
        val effect = settings.effect.takeIf { mode == Mode.Photo && it in capabilities.effects }
        if (effect == null || effect == Effect.None) return selector
        return extensions.getExtensionEnabledCameraSelector(selector, effect.mode)
    }

    private fun useCases(
        mode: Mode,
        settings: Settings,
        surface: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer?,
        safe: Boolean
    ): Array<UseCase> {
        val ratio = if (mode == Mode.Video) Ratio.Wide else settings.ratio
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(ratio.value, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            )
            .build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        preview.surfaceProvider = surface
        imageCapture = null
        videoCapture = null

        val output = when (mode) {
            Mode.Photo, Mode.Pro -> imageCapture(mode, settings, resolution, safe)
                .also { imageCapture = it }
            Mode.Video -> videoCapture(settings, safe).also { videoCapture = it }
            Mode.Scan -> null
        }
        val analysis = analyzer?.takeIf { !safe || mode == Mode.Scan }?.let {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(worker, it) }
        }
        return listOfNotNull(preview, output, analysis).toTypedArray()
    }

    private fun imageCapture(
        mode: Mode,
        settings: Settings,
        resolution: ResolutionSelector,
        safe: Boolean
    ): ImageCapture {
        val format = when {
            safe -> ImageCapture.OUTPUT_FORMAT_JPEG
            mode == Mode.Pro && settings.raw && capabilities.raw ->
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG
            mode == Mode.Photo && settings.ultraHdr && capabilities.ultraHdr &&
                settings.effect == Effect.None -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
            else -> ImageCapture.OUTPUT_FORMAT_JPEG
        }
        return ImageCapture.Builder()
            .setResolutionSelector(resolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(settings.flash.mode)
            .setJpegQuality(95)
            .setOutputFormat(format)
            .setTargetRotation(rotation)
            .build()
    }

    private fun videoCapture(settings: Settings, safe: Boolean): VideoCapture<Recorder> {
        val quality = QualitySelector.from(
            settings.resolution.quality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder().setQualitySelector(quality).build()
        val hdr = !safe && settings.hdrVideo && capabilities.hdrVideo
        return VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .setTargetRotation(rotation)
            .setDynamicRange(if (hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR)
            .setVideoStabilizationEnabled(
                !safe && settings.stabilization && capabilities.stabilization
            )
            .apply {
                if (!safe && settings.smooth && capabilities.smooth) {
                    setTargetFrameRate(Range(60, 60))
                }
            }
            .build()
    }

    private fun capabilitiesOf(
        info: CameraInfo,
        selector: CameraSelector,
        switchable: Boolean
    ): Capabilities {
        val camera2 = Camera2CameraInfo.from(info)
        val video = Recorder.getVideoCapabilities(info)
        val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
        val manual = camera2.getCameraCharacteristic(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val iso = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )?.takeIf { manual }
        val exposure = camera2.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )?.takeIf { manual }
        val minFocus = camera2.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )?.takeIf { manual } ?: 0f

        return Capabilities(
            switchable = switchable,
            flash = info.hasFlashUnit(),
            torchLevels = if (info.isTorchStrengthSupported) info.maxTorchStrengthLevel else 0,
            effects = Effect.entries.filter {
                it == Effect.None ||
                    extensions?.isExtensionAvailable(selector, it.mode) == true
            },
            ultraHdr = ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats,
            raw = ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats,
            resolutions = Resolution.entries.filter {
                video.isQualitySupported(it.quality, DynamicRange.SDR)
            },
            smooth = info.supportedFrameRateRanges.any { it.upper >= 60 },
            stabilization = video.isStabilizationSupported,
            hdrVideo = DynamicRange.HLG_10_BIT in video.supportedDynamicRanges,
            isoSteps = ISO_STEPS.filter { iso?.contains(it) == true },
            shutterSteps = SHUTTER_STEPS.filter { exposure?.contains(it) == true },
            minFocus = minFocus
        )
    }

    fun rotate(value: Int) {
        rotation = value
        imageCapture?.targetRotation = value
        videoCapture?.targetRotation = value
    }

    fun takePhoto(settings: Settings, file: File?, onSaved: (Uri?) -> Unit) {
        val capture = imageCapture ?: return onSaved(null)
        capture.flashMode = settings.flash.mode
        if (settings.sound || CameraInfo.mustPlayShutterSound()) {
            sound.play(MediaActionSound.SHUTTER_CLICK)
        }

        val front = bound?.cameraInfo?.lensFacing == CameraSelector.LENS_FACING_FRONT
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = front && settings.mirror
        }
        val name = "IMG_${stamp()}"
        val jpeg = when (file) {
            null -> mediaOptions(name, "image/jpeg")
            else -> ImageCapture.OutputFileOptions.Builder(file)
        }.setMetadata(metadata).build()
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) =
                onSaved(output.savedUri)

            override fun onError(exception: ImageCaptureException) = onSaved(null)
        }

        if (capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG && file == null) {
            val raw = mediaOptions(name, "image/x-adobe-dng").setMetadata(metadata).build()
            capture.takePicture(raw, jpeg, main, callback)
        } else {
            capture.takePicture(jpeg, main, callback)
        }
    }

    private fun mediaOptions(name: String, mime: String) = ImageCapture.OutputFileOptions.Builder(
        activity.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        mediaValues(name, mime)
    )

    fun record(
        audio: Boolean,
        target: ParcelFileDescriptor?,
        durationLimit: Long,
        sizeLimit: Long,
        onFinished: (Uri?) -> Unit
    ) {
        val recorder = videoCapture?.output ?: return onFinished(null)
        val pending = if (target != null) {
            val options = FileDescriptorOutputOptions.Builder(target)
                .setDurationLimitMillis(durationLimit)
                .setFileSizeLimit(sizeLimit)
                .build()
            recorder.prepareRecording(activity, options)
        } else {
            val options = MediaStoreOutputOptions.Builder(
                activity.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(mediaValues("VID_${stamp()}", "video/mp4"))
                .setDurationLimitMillis(durationLimit)
                .setFileSizeLimit(sizeLimit)
                .build()
            recorder.prepareRecording(activity, options)
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.withMicrophone(audio).start(main) { event ->
            when (event) {
                is VideoRecordEvent.Status -> recorded = event.recordingStats.recordedDurationNanos
                is VideoRecordEvent.Pause -> paused = true
                is VideoRecordEvent.Resume -> paused = false
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    paused = false
                    recorded = 0
                    target?.close()
                    sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    val saved = event.error in FINISHED
                    onFinished(event.outputResults.outputUri.takeIf { saved })
                }
            }
        }
    }

    private fun PendingRecording.withMicrophone(audio: Boolean): PendingRecording {
        val permission = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        if (!audio || permission != PackageManager.PERMISSION_GRANTED) return this
        return withAudioEnabled()
    }

    fun stopRecording() = recording?.stop()

    fun togglePause() {
        val recording = recording ?: return
        if (paused) recording.resume() else recording.pause()
    }

    fun focus(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        bound?.cameraControl?.startFocusAndMetering(action)
    }

    fun zoom(ratio: Float) {
        val camera = bound ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        camera.cameraControl.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    fun exposure(index: Int) = bound?.cameraControl?.setExposureCompensationIndex(index)

    fun torch(on: Boolean) {
        torch = on && capabilities.flash
        bound?.cameraControl?.enableTorch(torch)
    }

    fun torchLevel(level: Int) {
        bound?.cameraControl?.setTorchStrengthLevel(level)
    }

    fun apply(manual: Manual) {
        val control = bound?.cameraControl ?: return
        val options = CaptureRequestOptions.Builder().apply {
            if (manual.exposure) {
                val exposure = manual.shutter ?: (1_000_000_000L / 60)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, manual.iso ?: 400)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, 33_333_333L))
            }
            if (manual.focus != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, manual.focus)
            }
            set(CaptureRequest.CONTROL_AWB_MODE, manual.balance.mode)
        }.build()
        runCatching { Camera2CameraControl.from(control).setCaptureRequestOptions(options) }
    }

    private fun <T : Any> CaptureRequestOptions.Builder.set(key: CaptureRequest.Key<T>, value: T) {
        setCaptureRequestOption(key, value)
    }
}

private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

private fun mediaValues(name: String, mime: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
}
