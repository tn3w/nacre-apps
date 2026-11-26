package org.nacre.gallery

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

enum class Sort(val label: String, val dated: Boolean) {
    Taken("Date taken", true),
    Modified("Date modified", true),
    Name("Name", false),
    Size("Size", false)
}

enum class Group { Day, Month, Year, None }

enum class Kind { All, Photos, Videos }

enum class AlbumSort { Recent, Name, Size }

data class Settings(
    val sort: Sort = Sort.Taken,
    val descending: Boolean = true,
    val group: Group = Group.Day,
    val kind: Kind = Kind.All,
    val columns: Int = 4,
    val albumSort: AlbumSort = AlbumSort.Recent
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sort", sort.name)
        .put("descending", descending)
        .put("group", group.name)
        .put("kind", kind.name)
        .put("columns", columns)
        .put("albumSort", albumSort.name)

    companion object {
        fun fromJson(json: JSONObject) = Settings().run {
            copy(
                sort = json.enum("sort", sort),
                descending = json.optBoolean("descending", descending),
                group = json.enum("group", group),
                kind = json.enum("kind", kind),
                columns = json.optInt("columns", columns).coerceIn(COLUMNS),
                albumSort = json.enum("albumSort", albumSort)
            )
        }

        val COLUMNS = 2..8
    }
}

private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T) =
    enumValues<T>().find { it.name == optString(key) } ?: default

object Store {
    private lateinit var preferences: SharedPreferences

    val settings = MutableStateFlow(Settings())

    fun init(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)
        settings.value =
            runCatching {
                Settings.fromJson(JSONObject(preferences.getString("settings", null)!!))
            }.getOrDefault(Settings())
    }

    fun update(change: Settings.() -> Settings) {
        settings.value = settings.value.change()
        preferences.edit { putString("settings", settings.value.toJson().toString()) }
    }
}
