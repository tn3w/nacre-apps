package org.nacre.camera

import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import org.nacre.core.NacreTheme

open class MainActivity : ComponentActivity() {
    private val shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val orientation = Orientation()
    private lateinit var camera: Camera
    private lateinit var launch: Launch
    private lateinit var listener: OrientationEventListener
    private val mode = mutableStateOf(Mode.Photo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(clear, clear)
        window.isNavigationBarContrastEnforced = false
        Store.init(applicationContext)
        val secure = this is SecureActivity &&
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (secure) setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        launch = launchOf(intent).let {
            if (secure) it.copy(secure = true, modes = it.modes - Mode.Scan) else it
        }
        if (launch.output == Uri.EMPTY) {
            setResult(RESULT_CANCELED)
            return finish()
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_FRONT, false)) {
            Store.update { copy(front = true) }
        }
        val start = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab")
        mode.value = launch.modes.find { it.name == start } ?: launch.start
        camera = Camera(this)
        listener = orientationListener()

        setContent {
            NacreTheme {
                Surface {
                    CameraApp(
                        this,
                        camera,
                        launch,
                        mode,
                        orientation,
                        shutter,
                        ::deliverPhoto,
                        ::deliverVideo
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", mode.value.name)
    }

    override fun onStart() {
        super.onStart()
        if (::listener.isInitialized) listener.enable()
    }

    override fun onStop() {
        super.onStop()
        if (::listener.isInitialized) listener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::camera.isInitialized) camera.close()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShutterKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) shutter.tryEmit(Unit)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        isShutterKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isShutterKey(keyCode: Int): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val volumeShutter = volume && Store.settings.value.volumeKeys
        return keyCode == KeyEvent.KEYCODE_CAMERA || volumeShutter
    }

    private fun orientationListener() = object : OrientationEventListener(this) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val snapped = (degrees + 45) / 90 % 4 * 90
            orientation.degrees = snapped
            orientation.tilt = ((degrees - snapped + 540) % 360 - 180).toFloat()
            camera.rotate(
                when (snapped) {
                    90 -> Surface.ROTATION_270
                    180 -> Surface.ROTATION_180
                    270 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            )
        }
    }

    private fun launchOf(intent: Intent): Launch = when (intent.action) {
        MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_IMAGE_CAPTURE_SECURE ->
            Launch(modes = listOf(Mode.Photo), capture = true, output = outputOf(intent))
        MediaStore.ACTION_VIDEO_CAPTURE -> Launch(
            start = Mode.Video,
            modes = listOf(Mode.Video),
            capture = true,
            output = outputOf(intent),
            durationLimit = intent.limit(MediaStore.EXTRA_DURATION_LIMIT) * 1000,
            sizeLimit = intent.limit(MediaStore.EXTRA_SIZE_LIMIT)
        )
        MediaStore.INTENT_ACTION_VIDEO_CAMERA -> Launch(start = Mode.Video)
        else -> Launch()
    }

    private fun Intent.limit(key: String): Long {
        val value = extras?.get(key) as? Number ?: return 0
        return value.toLong().coerceIn(0, Int.MAX_VALUE.toLong())
    }

    private fun outputOf(intent: Intent): Uri? {
        val uri = runCatching {
            intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        }.getOrNull() ?: return null
        val safe = uri.scheme == ContentResolver.SCHEME_CONTENT && !ownsMedia(uri)
        return if (safe) uri else Uri.EMPTY
    }

    private fun ownsMedia(uri: Uri): Boolean {
        if (uri.authority?.substringAfterLast('@') != MediaStore.AUTHORITY) return false
        val columns = arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val owner = runCatching {
            contentResolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return owner == null || owner == packageName
    }

    private fun deliverVideo(uri: Uri?) {
        val result = when {
            uri == null -> null
            launch.output != null -> Intent().setData(launch.output)
            else -> Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(if (result == null) RESULT_CANCELED else RESULT_OK, result)
        finish()
    }

    private fun deliverPhoto(file: File) {
        val output = launch.output
        val result = runCatching {
            if (output == null) return@runCatching inlineThumbnail(file)
            contentResolver.openOutputStream(output, "wt")!!.use { stream ->
                file.inputStream().use { it.copyTo(stream) }
            }
            Intent()
        }
        file.delete()
        val code = if (result.isSuccess) RESULT_OK else RESULT_CANCELED
        setResult(code, result.getOrNull())
        finish()
    }

    private fun inlineThumbnail(file: File): Intent {
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = 512f / maxOf(info.size.width, info.size.height)
            if (scale < 1) {
                val width = (info.size.width * scale).toInt()
                decoder.setTargetSize(width, (info.size.height * scale).toInt())
            }
        }
        return Intent("inline-data").putExtra("data", bitmap)
    }
}

class SecureActivity : MainActivity()

private const val EXTRA_FRONT = "android.intent.extra.USE_FRONT_CAMERA"

class TorchActivity : ComponentActivity() {
    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private var callback: CameraManager.TorchCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = manager.cameraIdList.firstOrNull {
            val characteristics = manager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return finish()

        val torch = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id || isFinishing) return
                runCatching { manager.setTorchMode(id, !enabled) }
                finish()
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) finish()
            }
        }
        callback = torch
        manager.registerTorchCallback(torch, Handler(mainLooper))
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let(manager::unregisterTorchCallback)
    }
}
