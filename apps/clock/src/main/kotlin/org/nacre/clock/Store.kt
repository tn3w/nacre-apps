package org.nacre.clock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ClockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val low = NotificationManager.IMPORTANCE_LOW
        val ringing = NotificationChannel(
            RingService.CHANNEL,
            "Ringing",
            NotificationManager.IMPORTANCE_HIGH
        )
        ringing.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                ringing,
                NotificationChannel(Timer.CHANNEL, "Timers", low),
                NotificationChannel(Alarm.UPCOMING_CHANNEL, "Upcoming alarms", low)
            )
        )
        Scheduler.rescheduleAll(this)
    }
}

object Store {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    val zones = MutableStateFlow(emptyList<String>())
    val alarms = MutableStateFlow(emptyList<Alarm>())
    val timers = MutableStateFlow(emptyList<Timer>())
    val stopwatch = MutableStateFlow(Stopwatch())

    operator fun get(option: Option) = preferences.getInt(option.name, option.default)

    operator fun set(option: Option, value: Int) {
        preferences.edit { putInt(option.name, value) }
    }

    fun init(application: Context) {
        context = application
        val storage = context.createDeviceProtectedStorageContext()
        storage.moveSharedPreferencesFrom(context, "clock")
        preferences = storage.getSharedPreferences("clock", Context.MODE_PRIVATE)
        val available = ZoneId.getAvailableZoneIds()
        zones.value = preferences.getString("zones", "").orEmpty()
            .split(",")
            .filter { it in available }
            .distinct()
        alarms.value = readList("alarms", Alarm::fromJson).distinctBy(Alarm::id)
        timers.value = readList("timers", Timer::fromJson).distinctBy(Timer::id)
        stopwatch.value = runCatching {
            Stopwatch.fromJson(JSONObject(preferences.getString("stopwatch", null)!!))
        }.getOrDefault(Stopwatch())
    }

    fun addZone(zone: String) = saveZones(zones.value - zone + zone)

    fun removeZone(zone: String) = saveZones(zones.value - zone)

    private fun saveZones(list: List<String>) {
        zones.value = list
        preferences.edit { putString("zones", list.joinToString(",")) }
    }

    fun alarm(id: Int) = alarms.value.find { it.id == id }

    fun addAlarm(alarm: Alarm): Alarm {
        val id = (alarms.value.maxOfOrNull { it.id } ?: 0) + 1
        return alarm.copy(id = id).also(::saveAlarm)
    }

    fun saveAlarm(alarm: Alarm) {
        val list = alarms.value.filter { it.id != alarm.id } + alarm
        alarms.value = list.sortedBy { it.hour * 60 + it.minute }
        writeList("alarms", alarms.value.map(Alarm::toJson))
        Scheduler.schedule(context, alarm)
    }

    fun deleteAlarm(alarm: Alarm) {
        alarms.value = alarms.value.filter { it.id != alarm.id }
        writeList("alarms", alarms.value.map(Alarm::toJson))
        Scheduler.schedule(context, alarm.copy(enabled = false, snoozedUntil = 0))
        silence("alarm:${alarm.id}")
    }

    fun skip(id: Int) {
        val alarm = alarm(id) ?: return
        val snoozed = alarm.snoozedUntil > System.currentTimeMillis()
        saveAlarm(
            when {
                snoozed -> alarm.copy(snoozedUntil = 0)
                alarm.days.isEmpty() -> alarm.copy(enabled = false)
                else -> alarm.copy(skipped = alarm.trigger() ?: 0)
            }
        )
    }

    fun snooze(id: Int) {
        val alarm = alarm(id) ?: return
        val until = System.currentTimeMillis() + Store[Option.Snooze] * 60_000L
        saveAlarm(alarm.copy(snoozedUntil = until))
    }

    fun timer(id: Int) = timers.value.find { it.id == id }

    fun newTimer(duration: Long, label: String = "") {
        val id = (timers.value.maxOfOrNull { it.id } ?: 0) + 1
        saveTimer(Timer(id, duration, label = label).toggled())
    }

    fun saveTimer(timer: Timer) {
        val exists = timers.value.any { it.id == timer.id }
        timers.value = if (exists) {
            timers.value.map { if (it.id == timer.id) timer else it }
        } else {
            timers.value + timer
        }
        writeList("timers", timers.value.map(Timer::toJson))
        Scheduler.schedule(context, timer)
        notifyTimer(context, timer)
        if (timer.left() > 0 || !timer.running) silence("timer:${timer.id}")
    }

    fun deleteTimer(timer: Timer) {
        timers.value = timers.value.filter { it.id != timer.id }
        writeList("timers", timers.value.map(Timer::toJson))
        Scheduler.cancel(context, "timer:${timer.id}")
        context.getSystemService(
            NotificationManager::class.java
        ).cancel("timer", timer.id)
        silence("timer:${timer.id}")
    }

    fun saveStopwatch(value: Stopwatch) {
        stopwatch.value = value
        preferences.edit { putString("stopwatch", value.toJson().toString()) }
        notifyStopwatch(context, value)
    }

    private fun silence(uri: String) = RingService.silence(context, uri)

    fun addMinute(id: Int) = timer(id)?.let { saveTimer(it.plusMinute()) }

    fun resetTimer(id: Int) = timer(id)?.let { saveTimer(it.reset()) }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val array = runCatching { JSONArray(preferences.getString(key, "[]")) }
            .getOrDefault(JSONArray())
        return List(array.length()) { it }.mapNotNull {
            runCatching { parse(array.getJSONObject(it)) }.getOrNull()
        }
    }

    private fun writeList(key: String, items: List<JSONObject>) {
        preferences.edit { putString(key, JSONArray(items).toString()) }
    }
}
