package org.nacre.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.media.MediaActionSound
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
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
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
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
import kotlin.concurrent.thread

enum class Mode(val label: String) {
    Photo("Photo"),
    Video("Video"),
    Pro("Pro"),
    Document("Document"),
    Panorama("Panorama"),
    Scan("Scan")
}

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
    val front: Boolean = false,
    val switchable: Boolean = false,
    val flash: Boolean = false,
    val torchLevels: Int = 0,
    val effects: List<Effect> = listOf(Effect.None),
    val ultraHdr: Boolean = false,
    val raw: Boolean = false,
    val resolutions: List<Resolution> = emptyList(),
    val smooth: Boolean = false,
    val slowMotion: Boolean = false,
    val stabilization: Boolean = false,
    val hdrVideo: Boolean = false,
    val isoSteps: List<Int> = emptyList(),
    val shutterSteps: List<Long> = emptyList(),
    val minFocus: Float = 0f
) {
    val speeds get() = Speed.entries.filter { it != Speed.Slow || slowMotion }
}

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
private val BITRATES = mapOf(
    Resolution.SD to 6_000_000,
    Resolution.HD to 14_000_000,
    Resolution.FHD to 28_000_000,
    Resolution.UHD to 70_000_000
)

class Camera(private val activity: ComponentActivity) {
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var rotation = Surface.ROTATION_0
    private val main = activity.mainExecutor
    private val handler = Handler(Looper.getMainLooper())
    private val sound = MediaActionSound()
    private var closed = false
    val worker = Executors.newSingleThreadExecutor()!!

    var location: Location? = null
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
    var screenFlash by mutableStateOf(false)
        private set

    private val screen = object : ImageCapture.ScreenFlash {
        override fun apply(expiration: Long, listener: ImageCapture.ScreenFlashListener) {
            screenFlash = true
            handler.postDelayed(listener::onCompleted, 400)
        }

        override fun clear() {
            screenFlash = false
        }
    }

    suspend fun open() {
        val provider = ProcessCameraProvider.awaitInstance(activity)
        extensions = runCatching { ExtensionsManager.getInstance(activity, provider) }.getOrNull()
        this.provider = provider
    }

    fun close() {
        closed = true
        recording?.stop()
        provider?.unbindAll()
        sound.release()
        worker.shutdown()
    }

    fun bind(
        mode: Mode,
        settings: Settings,
        preview: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer?
    ) {
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
            bindWith(provider, mode, settings, selector, preview, analyzer, safe = false)
        }.recoverCatching {
            provider.unbindAll()
            bindWith(provider, mode, settings, selector, preview, analyzer, safe = true)
        }.getOrNull()
        if (torch) bound?.cameraControl?.enableTorch(true)
        if (mode != Mode.Pro) apply(Manual())
    }

    private fun bindWith(
        provider: ProcessCameraProvider,
        mode: Mode,
        settings: Settings,
        selector: CameraSelector,
        surface: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer?,
        safe: Boolean
    ): androidx.camera.core.Camera {
        val ratio = if (mode == Mode.Video) Ratio.Wide else settings.ratio
        val preview = Preview.Builder().setResolutionSelector(selectorOf(ratio, false)).build()
        preview.surfaceProvider = surface
        imageCapture = null
        videoCapture = null

        val slow = !safe && mode == Mode.Video && settings.speed == Speed.Slow &&
            capabilities.slowMotion
        if (slow) {
            val video = VideoCapture.withOutput(Recorder.Builder().build())
            videoCapture = video
            val session = HighSpeedVideoSessionConfig.Builder(video)
                .setPreview(preview)
                .setSlowMotionEnabled(true)
                .build()
            return provider.bindToLifecycle(activity, selector, session)
        }

        val useCases = mutableListOf<UseCase>(preview)
        if (mode in listOf(Mode.Photo, Mode.Pro, Mode.Document)) {
            useCases += imageCapture(mode, settings, ratio, safe).also { imageCapture = it }
        }
        val quickVideo = mode == Mode.Photo && settings.hold == Hold.Video && !safe
        if (mode == Mode.Video || quickVideo) {
            useCases += videoCapture(settings, safe).also { videoCapture = it }
        }
        if (analyzer != null && (!safe || mode in listOf(Mode.Scan, Mode.Panorama))) {
            val size = if (mode == Mode.Panorama) Size(1920, 1440) else Size(1280, 960)
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            useCases += ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(
                    if (mode == Mode.Document || mode == Mode.Panorama) {
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    } else {
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                    }
                )
                .build()
                .apply { setAnalyzer(worker, analyzer) }
        }
        val target = if (safe) selector else selectorFor(mode, settings, selector)
        return provider.bindToLifecycle(activity, target, *useCases.toTypedArray())
    }

    private fun selectorOf(ratio: Ratio, highest: Boolean): ResolutionSelector {
        val builder = ResolutionSelector.Builder().setAspectRatioStrategy(
            AspectRatioStrategy(ratio.value, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        )
        if (!highest) return builder.build()
        return builder
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()
    }

    private fun selectorFor(mode: Mode, settings: Settings, selector: CameraSelector): CameraSelector {
        val extensions = extensions ?: return selector
        val effect = settings.effect.takeIf { mode == Mode.Photo && it in capabilities.effects }
        if (effect == null || effect == Effect.None) return selector
        return extensions.getExtensionEnabledCameraSelector(selector, effect.mode)
    }

    private fun imageCapture(mode: Mode, settings: Settings, ratio: Ratio, safe: Boolean): ImageCapture {
        val format = when {
            safe -> ImageCapture.OUTPUT_FORMAT_JPEG
            mode == Mode.Pro && settings.raw && capabilities.raw ->
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG
            mode == Mode.Photo && settings.ultraHdr && capabilities.ultraHdr &&
                settings.effect == Effect.None -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
            else -> ImageCapture.OUTPUT_FORMAT_JPEG
        }
        val builder = ImageCapture.Builder()
            .setResolutionSelector(selectorOf(ratio, highest = !safe))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(98)
            .setOutputFormat(format)
            .setTargetRotation(rotation)
        if (!safe) {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
                .setCaptureRequestOption(
                    CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                )
                .setCaptureRequestOption(
                    CaptureRequest.SHADING_MODE,
                    CaptureRequest.SHADING_MODE_HIGH_QUALITY
                )
                .setCaptureRequestOption(
                    CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                )
        }
        return builder.build().apply { screenFlash = screen }
    }

    private fun videoCapture(settings: Settings, safe: Boolean): VideoCapture<Recorder> {
        val quality = QualitySelector.from(
            settings.resolution.quality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val bitrate = BITRATES.getValue(settings.resolution) * if (settings.smooth) 3 / 2 else 1
        val recorder = Recorder.Builder()
            .setQualitySelector(quality)
            .apply { if (!safe) setTargetVideoEncodingBitRate(bitrate) }
            .build()
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
        val highSpeed = runCatching { Recorder.getHighSpeedVideoCapabilities(info) }.getOrNull()
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
            front = info.lensFacing == CameraSelector.LENS_FACING_FRONT,
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
            slowMotion = highSpeed?.getSupportedQualities(DynamicRange.SDR)?.isNotEmpty() == true,
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

    fun takePhoto(settings: Settings, file: File?, quiet: Boolean = false, onSaved: (Uri?) -> Unit) {
        val capture = imageCapture ?: return onSaved(null)
        val flash = settings.flash != Flash.Off
        capture.flashMode = when {
            capabilities.front && flash -> ImageCapture.FLASH_MODE_SCREEN
            capabilities.front -> ImageCapture.FLASH_MODE_OFF
            else -> settings.flash.mode
        }
        if (!quiet && (settings.sound || CameraInfo.mustPlayShutterSound())) {
            sound.play(MediaActionSound.SHUTTER_CLICK)
        }

        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = capabilities.front && settings.mirror
            location = this@Camera.location
        }
        val name = "IMG_${stamp()}"
        val jpeg = when (file) {
            null -> mediaOptions(name, "image/jpeg")
            else -> ImageCapture.OutputFileOptions.Builder(file)
        }.setMetadata(metadata).build()
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) =
                onSaved(output.savedUri ?: file?.let(Uri::fromFile))

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
        settings: Settings,
        target: ParcelFileDescriptor?,
        durationLimit: Long,
        sizeLimit: Long,
        onFinished: (Uri?) -> Unit
    ) {
        val recorder = videoCapture?.output
        if (recording != null || recorder == null) {
            target?.close()
            return onFinished(null)
        }
        val lapse = settings.speed == Speed.Lapse && target == null
        val lapseFile = File(activity.cacheDir, "lapse.mp4")
        val pending = when {
            lapse -> recorder.prepareRecording(activity, FileOutputOptions.Builder(lapseFile).build())
            target != null -> {
                val options = FileDescriptorOutputOptions.Builder(target)
                    .setDurationLimitMillis(durationLimit)
                    .setFileSizeLimit(sizeLimit)
                    .build()
                recorder.prepareRecording(activity, options)
            }
            else -> {
                val options = MediaStoreOutputOptions.Builder(
                    activity.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                    .setContentValues(mediaValues("VID_${stamp()}", "video/mp4"))
                    .setDurationLimitMillis(durationLimit)
                    .setFileSizeLimit(sizeLimit)
                    .setLocation(location)
                    .build()
                recorder.prepareRecording(activity, options)
            }
        }
        val audio = settings.audio && !lapse
        recording = runCatching {
            pending.withMicrophone(audio).start(main) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        recorded = event.recordingStats.recordedDurationNanos
                    is VideoRecordEvent.Pause -> paused = true
                    is VideoRecordEvent.Resume -> paused = false
                    is VideoRecordEvent.Finalize -> {
                        target?.close()
                        finished(event, lapseFile.takeIf { lapse }, onFinished)
                    }
                }
            }
        }.getOrElse {
            target?.close()
            return onFinished(null)
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
    }

    private fun finished(
        event: VideoRecordEvent.Finalize,
        lapseFile: File?,
        onFinished: (Uri?) -> Unit
    ) {
        recording = null
        paused = false
        recorded = 0
        if (!closed) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        val uri = event.outputResults.outputUri.takeIf { event.error in FINISHED }
        if (lapseFile == null || uri == null) return onFinished(uri)
        thread {
            val saved = saveTimelapse(activity, lapseFile, "VID_${stamp()}")
            main.execute { onFinished(saved) }
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

    fun zoomBy(factor: Float) {
        val current = bound?.cameraInfo?.zoomState?.value ?: return
        zoom(current.zoomRatio * factor)
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

fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

fun mediaValues(name: String, mime: String) = ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
}
