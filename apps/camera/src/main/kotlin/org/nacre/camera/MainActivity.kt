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

        launch = launchOf(intent).copy(secure = secure)
        if (launch.output == Uri.EMPTY) {
            setResult(RESULT_CANCELED)
            return finish()
        }
        val start = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab")
        val mode = mutableStateOf(
            launch.modes.find { it.name == start } ?: launch.start
        )
        camera = Camera(this)
        listener = orientationListener()

        setContent {
            NacreTheme {
                Surface { CameraApp(this, camera, launch, mode, orientation, shutter, ::deliverPhoto) {
                    setResult(
                        RESULT_OK,
                        Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    finish()
                } }
            }
        }
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
        return keyCode == KeyEvent.KEYCODE_CAMERA || volume && Store.settings.value.volumeKeys
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
            durationLimit = intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0) * 1000L,
            sizeLimit = intent.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0)
        )
        MediaStore.INTENT_ACTION_VIDEO_CAMERA -> Launch(start = Mode.Video)
        else -> Launch()
    }

    private fun outputOf(intent: Intent): Uri? {
        val uri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            ?: return null
        val safe = uri.scheme == ContentResolver.SCHEME_CONTENT && !ownsMedia(uri)
        return if (safe) uri else Uri.EMPTY
    }

    private fun ownsMedia(uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        val columns = arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val owner = runCatching {
            contentResolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return owner == null || owner == packageName
    }

    private fun deliverPhoto(file: File) {
        val output = launch.output
        val result = runCatching {
            if (output != null) {
                contentResolver.openOutputStream(output, "wt")!!.use { stream ->
                    file.inputStream().use { it.copyTo(stream) }
                }
                Intent()
            } else {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = 512f / maxOf(info.size.width, info.size.height)
                    if (scale < 1) {
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt(),
                            (info.size.height * scale).toInt()
                        )
                    }
                }
                Intent("inline-data").putExtra("data", bitmap)
            }
        }
        file.delete()
        result.onSuccess { setResult(RESULT_OK, it) }.onFailure { setResult(RESULT_CANCELED) }
        finish()
    }
}

class SecureActivity : MainActivity()

class TorchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return finish()

        manager.registerTorchCallback(
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId != id) return
                    manager.unregisterTorchCallback(this)
                    runCatching { manager.setTorchMode(id, !enabled) }
                    finish()
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == id) finish()
                }
            },
            Handler(mainLooper)
        )
    }
}
