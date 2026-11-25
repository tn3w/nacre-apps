package org.nacre.messages

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

private const val DRAFT_LIMIT = 4000

class MessagesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) return
        Store.init(this)
        val channel =
            NotificationChannel(
                MESSAGES_CHANNEL,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            )
        getSystemService(
            NotificationManager::class.java,
        ).createNotificationChannel(channel)
    }
}

enum class AutoDownload(
    val label: String,
) {
    Contacts("From contacts only"),
    Always("From everyone"),
    Never("Never"),
}

object Store {
    private lateinit var preferences: SharedPreferences

    val pinned = MutableStateFlow(emptySet<Long>())
    val archived = MutableStateFlow(emptySet<Long>())
    val muted = MutableStateFlow(emptySet<Long>())
    val drafts = MutableStateFlow(emptyMap<Long, String>())
    val deliveryReports = MutableStateFlow(false)
    val notifyUnknown = MutableStateFlow(true)
    val autoDownload = MutableStateFlow(AutoDownload.Contacts)

    fun init(context: Context) {
        preferences = context.getSharedPreferences("messages", Context.MODE_PRIVATE)
        pinned.value = readIds("pinned")
        archived.value = readIds("archived")
        muted.value = readIds("muted")
        drafts.value = readDrafts()
        deliveryReports.value = preferences.getBoolean("deliveryReports", false)
        notifyUnknown.value = preferences.getBoolean("notifyUnknown", true)
        autoDownload.value =
            AutoDownload.entries.find {
                it.name == preferences.getString("autoDownload", null)
            } ?: AutoDownload.Contacts
    }

    private fun readIds(key: String) =
        preferences
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()

    private fun writeIds(
        key: String,
        flow: MutableStateFlow<Set<Long>>,
        ids: Set<Long>,
    ) {
        flow.value = ids
        preferences.edit { putStringSet(key, ids.map(Long::toString).toSet()) }
    }

    fun setPinned(
        thread: Long,
        value: Boolean,
    ) = writeIds("pinned", pinned, pinned.value.toggled(thread, value))

    fun setArchived(
        thread: Long,
        value: Boolean,
    ) = writeIds("archived", archived, archived.value.toggled(thread, value))

    fun setMuted(
        thread: Long,
        value: Boolean,
    ) = writeIds("muted", muted, muted.value.toggled(thread, value))

    fun forget(threads: Set<Long>) {
        writeIds("pinned", pinned, pinned.value - threads)
        writeIds("archived", archived, archived.value - threads)
        writeIds("muted", muted, muted.value - threads)
        writeDrafts(drafts.value - threads)
    }

    fun setDraft(
        thread: Long,
        text: String,
    ) {
        val trimmed = text.take(DRAFT_LIMIT)
        if (drafts.value[thread].orEmpty() == trimmed) return
        writeDrafts(
            if (trimmed.isBlank()) {
                drafts.value - thread
            } else {
                drafts.value +
                    (thread to trimmed)
            },
        )
    }

    private fun readDrafts(): Map<Long, String> {
        val json =
            runCatching { JSONObject(preferences.getString("drafts", "{}")!!) }
                .getOrDefault(JSONObject())
        return json
            .keys()
            .asSequence()
            .mapNotNull { key -> key.toLongOrNull()?.let { it to json.optString(key) } }
            .toMap()
    }

    private fun writeDrafts(map: Map<Long, String>) {
        drafts.value = map
        val json = JSONObject(map.mapKeys { it.key.toString() })
        preferences.edit { putString("drafts", json.toString()) }
    }

    fun setDeliveryReports(value: Boolean) {
        deliveryReports.value = value
        preferences.edit { putBoolean("deliveryReports", value) }
    }

    fun setNotifyUnknown(value: Boolean) {
        notifyUnknown.value = value
        preferences.edit { putBoolean("notifyUnknown", value) }
    }

    fun setAutoDownload(value: AutoDownload) {
        autoDownload.value = value
        preferences.edit { putString("autoDownload", value.name) }
    }
}

private fun Set<Long>.toggled(
    id: Long,
    value: Boolean,
) = if (value) this + id else this - id
