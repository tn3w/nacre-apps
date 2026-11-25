package org.nacre.clock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ClockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                RingService.CHANNEL,
                "Ringing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setSound(null, null) }
        )
    }
}

object Store {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    val zones = MutableStateFlow(emptyList<String>())
    val alarms = MutableStateFlow(emptyList<Alarm>())

    val snoozeMinutes get() = 10
    val silenceMinutes get() = 10
    val rampSeconds get() = 30

    fun init(application: Context) {
        context = application
        preferences = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
        zones.value =
            preferences.getString("zones", "")!!.split(",").filter { it.isNotEmpty() }
        alarms.value = readList("alarms", Alarm::fromJson)
    }

    fun addZone(zone: String) = saveZones(zones.value - zone + zone)

    fun removeZone(zone: String) = saveZones(zones.value - zone)

    private fun saveZones(list: List<String>) {
        zones.value = list
        preferences.edit().putString("zones", list.joinToString(",")).apply()
    }

    fun alarm(id: Int) = alarms.value.find { it.id == id }

    fun newAlarm(hour: Int, minute: Int): Alarm {
        val alarm = Alarm((alarms.value.maxOfOrNull { it.id } ?: 0) + 1, hour, minute)
        saveAlarm(alarm)
        return alarm
    }

    fun saveAlarm(alarm: Alarm) {
        val list = alarms.value.filter { it.id != alarm.id } + alarm
        alarms.value = list.sortedBy { it.hour * 60 + it.minute }
        writeList("alarms", alarms.value.map(Alarm::toJson))
        Scheduler.schedule(context, alarm)
    }

    fun deleteAlarm(alarm: Alarm) {
        alarms.value -= alarm
        writeList("alarms", alarms.value.map(Alarm::toJson))
        Scheduler.cancel(context, alarm)
    }

    fun snooze(id: Int) {
        val alarm = alarm(id) ?: return
        saveAlarm(
            alarm.copy(
                snoozedUntil =
                    System.currentTimeMillis() + snoozeMinutes * 60_000L
            )
        )
    }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val array = JSONArray(preferences.getString(key, "[]"))
        return List(array.length()) { parse(array.getJSONObject(it)) }
    }

    private fun writeList(key: String, items: List<JSONObject>) {
        preferences.edit().putString(key, JSONArray(items).toString()).apply()
    }
}
