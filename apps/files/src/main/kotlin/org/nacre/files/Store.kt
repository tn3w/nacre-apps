package org.nacre.files

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class Sort(val label: String) {
    Name("Name"),
    Modified("Date modified"),
    Size("Size"),
    Type("Type")
}

data class Settings(
    val sort: Sort = Sort.Name,
    val descending: Boolean = false,
    val hidden: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sort", sort.name)
        .put("descending", descending)
        .put("hidden", hidden)

    companion object {
        fun fromJson(json: JSONObject) = Settings(
            sort =
                Sort.entries.find { it.name == json.optString("sort") } ?: Sort.Name,
            descending = json.optBoolean("descending"),
            hidden = json.optBoolean("hidden")
        )
    }
}

data class Trashed(val path: String, val original: String, val deleted: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("original", original)
        .put("deleted", deleted)

    val expired get() = System.currentTimeMillis() - deleted > TimeUnit.DAYS.toMillis(30)
}

object Store {
    private lateinit var preferences: SharedPreferences

    val settings = MutableStateFlow(Settings())
    val starred = MutableStateFlow(emptySet<String>())
    val trash = MutableStateFlow(emptyList<Trashed>())

    fun init(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.getSharedPreferences("files", Context.MODE_PRIVATE)
        settings.value =
            runCatching { Settings.fromJson(JSONObject(read("settings"))) }
                .getOrDefault(Settings())
        starred.value = array("starred") { getString(it) }.toSet()
        trash.value =
            array("trash") {
                getJSONObject(it).run {
                    Trashed(getString("path"), getString("original"), getLong("deleted"))
                }
            }
    }

    private fun read(key: String) = preferences.getString(key, null)!!

    private fun <T> array(key: String, item: JSONArray.(Int) -> T): List<T> =
        runCatching {
            JSONArray(read(key)).let { json ->
                List(json.length()) { json.item(it) }
            }
        }.getOrDefault(emptyList())

    fun update(change: Settings.() -> Settings) {
        settings.value = settings.value.change()
        preferences.edit { putString("settings", settings.value.toJson().toString()) }
    }

    fun star(paths: List<String>, on: Boolean) {
        starred.value = if (on) starred.value + paths else starred.value - paths.toSet()
        preferences.edit { putString("starred", JSONArray(starred.value).toString()) }
    }

    fun updateTrash(change: List<Trashed>.() -> List<Trashed>) {
        trash.value = trash.value.change()
        val json = JSONArray(trash.value.map { it.toJson() })
        preferences.edit { putString("trash", json.toString()) }
    }
}
