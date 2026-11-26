package org.nacre.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.video.Quality
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

enum class Flash(val label: String, val mode: Int) {
    Off("Off", ImageCapture.FLASH_MODE_OFF),
    Auto("Auto", ImageCapture.FLASH_MODE_AUTO),
    On("On", ImageCapture.FLASH_MODE_ON)
}

enum class Ratio(val label: String, val value: Int, val portrait: Float) {
    Standard("4:3", AspectRatio.RATIO_4_3, 3f / 4f),
    Wide("16:9", AspectRatio.RATIO_16_9, 9f / 16f)
}

enum class Resolution(val label: String, val quality: Quality) {
    SD("480p", Quality.SD),
    HD("720p", Quality.HD),
    FHD("1080p", Quality.FHD),
    UHD("4K", Quality.UHD)
}

enum class Effect(val label: String, val mode: Int) {
    None("Off", ExtensionMode.NONE),
    Auto("Auto", ExtensionMode.AUTO),
    Hdr("HDR", ExtensionMode.HDR),
    Night("Night", ExtensionMode.NIGHT),
    Bokeh("Portrait", ExtensionMode.BOKEH)
}

enum class Hold(val label: String) { Burst("Burst"), Video("Quick video") }

enum class Speed(val label: String) {
    Normal("1×"),
    Lapse("Time-lapse"),
    Slow("Slow motion")
}

data class Settings(
    val flash: Flash = Flash.Off,
    val timer: Int = 0,
    val ratio: Ratio = Ratio.Standard,
    val effect: Effect = Effect.None,
    val front: Boolean = false,
    val grid: Boolean = false,
    val level: Boolean = false,
    val sound: Boolean = true,
    val mirror: Boolean = true,
    val volumeKeys: Boolean = true,
    val ultraHdr: Boolean = true,
    val raw: Boolean = false,
    val resolution: Resolution = Resolution.FHD,
    val smooth: Boolean = false,
    val audio: Boolean = true,
    val stabilization: Boolean = true,
    val hdrVideo: Boolean = false,
    val hold: Hold = Hold.Burst,
    val speed: Speed = Speed.Normal,
    val location: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("flash", flash.name)
        .put("timer", timer)
        .put("ratio", ratio.name)
        .put("effect", effect.name)
        .put("front", front)
        .put("grid", grid)
        .put("level", level)
        .put("sound", sound)
        .put("mirror", mirror)
        .put("volumeKeys", volumeKeys)
        .put("ultraHdr", ultraHdr)
        .put("raw", raw)
        .put("resolution", resolution.name)
        .put("smooth", smooth)
        .put("audio", audio)
        .put("stabilization", stabilization)
        .put("hdrVideo", hdrVideo)
        .put("hold", hold.name)
        .put("speed", speed.name)
        .put("location", location)

    companion object {
        fun fromJson(json: JSONObject) = Settings().run {
            copy(
                flash = json.enum("flash", flash),
                timer = json.optInt("timer", timer).takeIf { it in TIMERS } ?: 0,
                ratio = json.enum("ratio", ratio),
                effect = json.enum("effect", effect),
                front = json.optBoolean("front", front),
                grid = json.optBoolean("grid", grid),
                level = json.optBoolean("level", level),
                sound = json.optBoolean("sound", sound),
                mirror = json.optBoolean("mirror", mirror),
                volumeKeys = json.optBoolean("volumeKeys", volumeKeys),
                ultraHdr = json.optBoolean("ultraHdr", ultraHdr),
                raw = json.optBoolean("raw", raw),
                resolution = json.enum("resolution", resolution),
                smooth = json.optBoolean("smooth", smooth),
                audio = json.optBoolean("audio", audio),
                stabilization = json.optBoolean("stabilization", stabilization),
                hdrVideo = json.optBoolean("hdrVideo", hdrVideo),
                hold = json.enum("hold", hold),
                speed = json.enum("speed", speed),
                location = json.optBoolean("location", location)
            )
        }

        val TIMERS = listOf(0, 3, 10)
    }
}

private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T) =
    enumValues<T>().find { it.name == optString(key) } ?: default

object Store {
    private lateinit var preferences: SharedPreferences

    val settings = MutableStateFlow(Settings())

    fun init(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
        settings.value = runCatching {
            Settings.fromJson(JSONObject(preferences.getString("settings", null)!!))
        }.getOrDefault(Settings())
    }

    fun update(change: Settings.() -> Settings) {
        settings.value = settings.value.change()
        preferences.edit { putString("settings", settings.value.toJson().toString()) }
    }
}
