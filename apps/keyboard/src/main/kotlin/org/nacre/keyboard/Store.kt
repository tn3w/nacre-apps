package org.nacre.keyboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Locale

class KeyboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

enum class Toggle(
    val title: String,
    val summary: String,
    val default: Boolean,
) {
    Suggestions("Show suggestions", "Word suggestions above the keys", true),
    Autocorrect("Auto-correction", "Fix typos when you press space or punctuation", true),
    AutoCaps("Auto-capitalization", "Capitalize the first word of each sentence", true),
    DoubleSpace("Double-space period", "Tap space twice to end a sentence", true),
    Learning("Learn new words", "Remember words you type, on this device only", true),
    NumberRow("Number row", "Always show digits above the letters", false),
    Popup("Key popup", "Enlarge keys while you press them", true),
    Haptics("Vibrate on keypress", "Short tap feedback", true),
    Sound("Sound on keypress", "Click when you press a key", false),
    Clipboard("Clipboard history", "Keep copied text for an hour, in memory only", true),
}

enum class Height(
    val row: Int,
) {
    Short(46),
    Normal(54),
    Tall(62),
}

data class Clip(
    val text: String,
    val time: Long,
)

object Store {
    private const val LIMIT = 3000
    private const val TRUSTED = 2
    const val CLIP_LIFETIME = 3_600_000L

    private lateinit var context: Context
    private lateinit var settings: SharedPreferences
    private var history: SharedPreferences? = null

    val toggles = MutableStateFlow(emptyMap<Toggle, Boolean>())
    val languages = MutableStateFlow(listOf(Language.English))
    val height = MutableStateFlow(Height.Normal)
    val words = MutableStateFlow(emptyMap<String, Int>())
    val pairs = MutableStateFlow(emptyMap<String, Int>())
    val recents = MutableStateFlow(emptyList<String>())
    val clips = MutableStateFlow(emptyList<Clip>())

    val unlocked get() = history != null

    val trusted get() = words.value.filterValues { it >= TRUSTED }

    operator fun get(toggle: Toggle) = toggles.value[toggle] ?: toggle.default

    operator fun set(
        toggle: Toggle,
        value: Boolean,
    ) {
        toggles.value += toggle to value
        settings.edit { putBoolean(toggle.name, value) }
        if (toggle == Toggle.Clipboard && !value) clips.value = emptyList()
    }

    fun init(application: Context) {
        context = application
        settings =
            context
                .createDeviceProtectedStorageContext()
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
        toggles.value =
            Toggle.entries.associateWith { settings.getBoolean(it.name, it.default) }
        height.value =
            Height.entries.find { it.name == settings.getString("height", null) }
                ?: Height.Normal
        val system = Language.of(Locale.getDefault().language)
        val saved =
            settings
                .getString(
                    "languages",
                    null,
                )?.split(",")
                ?.mapNotNull(Language::of)
        languages.value =
            saved?.ifEmpty { null } ?: listOfNotNull(system ?: Language.English)
        unlock()
    }

    fun unlock() {
        if (history != null) return
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val preferences = context.getSharedPreferences("history", Context.MODE_PRIVATE)
        words.value = readCounts(preferences, "words")
        pairs.value = readCounts(preferences, "pairs")
        recents.value =
            preferences
                .getString("recents", "")
                .orEmpty()
                .split(" ")
                .filter(String::isNotEmpty)
        history = preferences
    }

    fun setHeight(value: Height) {
        height.value = value
        settings.edit { putString("height", value.name) }
    }

    fun setLanguages(list: List<Language>) {
        if (list.isEmpty()) return
        languages.value = list
        settings.edit { putString("languages", list.joinToString(",") { it.code }) }
    }

    fun cycleLanguage() {
        val list = languages.value
        setLanguages(list.drop(1) + list.take(1))
    }

    fun learn(
        word: String,
        explicit: Boolean,
    ) {
        if (!unlocked) return
        val count = words.value[word] ?: 0
        saveWords(
            words.value + (word to if (explicit) maxOf(count, TRUSTED) else count + 1),
        )
    }

    fun forget(word: String) = saveWords(words.value - word)

    fun follow(
        previous: String,
        word: String,
    ) {
        if (!unlocked) return
        val key = "$previous $word"
        val updated = pairs.value + (key to (pairs.value[key] ?: 0) + 1)
        pairs.value = trim(updated)
        writeCounts("pairs", pairs.value)
    }

    fun predict(previous: String) =
        pairs.value
            .filterKeys { it.startsWith("$previous ") }
            .entries
            .sortedByDescending { it.value }
            .map { it.key.substringAfter(' ') }
            .take(3)

    fun clearHistory() {
        saveWords(emptyMap())
        pairs.value = emptyMap()
        recents.value = emptyList()
        history?.edit { clear() }
    }

    fun useEmoji(emoji: String) {
        if (!unlocked) return
        recents.value = (listOf(emoji) + (recents.value - emoji)).take(32)
        history?.edit { putString("recents", recents.value.joinToString(" ")) }
    }

    fun addClip(text: String) {
        if (!this[Toggle.Clipboard] || text.isBlank()) return
        val now = System.currentTimeMillis()
        clips.value =
            (listOf(Clip(text, now)) + clips.value.filter { it.text != text })
                .filter { now - it.time < CLIP_LIFETIME }
                .take(20)
    }

    fun expireClips() {
        val now = System.currentTimeMillis()
        clips.value = clips.value.filter { now - it.time < CLIP_LIFETIME }
    }

    fun removeClip(clip: Clip) {
        clips.value -= clip
    }

    private fun saveWords(map: Map<String, Int>) {
        words.value = trim(map)
        writeCounts("words", words.value)
    }

    private fun trim(map: Map<String, Int>) =
        if (map.size <= LIMIT) {
            map
        } else {
            map.entries
                .sortedByDescending { it.value }
                .take(LIMIT)
                .associate { it.toPair() }
        }

    private fun readCounts(
        preferences: SharedPreferences,
        key: String,
    ): Map<String, Int> {
        val json =
            runCatching { JSONObject(preferences.getString(key, "{}")!!) }
                .getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.optInt(it) }
    }

    private fun writeCounts(
        key: String,
        map: Map<String, Int>,
    ) {
        history?.edit { putString(key, JSONObject(map).toString()) }
    }
}
