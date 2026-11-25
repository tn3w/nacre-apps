package org.nacre.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.json.JSONObject

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val ringtone: String? = null,
    val snoozedUntil: Long = 0
) {
    fun next(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val today = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        return (0..7L)
            .map { today.plusDays(it) }
            .first { it.isAfter(now) && (days.isEmpty() || it.dayOfWeek in days) }
    }

    fun trigger(now: Long = System.currentTimeMillis()): Long? {
        val snooze = snoozedUntil.takeIf { it > now }
        val upcoming = next().toInstant().toEpochMilli().takeIf { enabled }
        return listOfNotNull(snooze, upcoming).minOrNull()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("days", days.sumOf { 1 shl it.ordinal })
        .put("label", label)
        .put("enabled", enabled)
        .put("vibrate", vibrate)
        .put("ringtone", ringtone)
        .put("snoozedUntil", snoozedUntil)

    companion object {
        fun fromJson(json: JSONObject) = Alarm(
            id = json.getInt("id"),
            hour = json.getInt("hour"),
            minute = json.getInt("minute"),
            days =
                DayOfWeek.entries
                    .filter {
                        json.getInt("days") shr it.ordinal and 1 ==
                            1
                    }.toSet(),
            label = json.getString("label"),
            enabled = json.getBoolean("enabled"),
            vibrate = json.getBoolean("vibrate"),
            ringtone = json.optString("ringtone").ifEmpty { null },
            snoozedUntil = json.getLong("snoozedUntil")
        )
    }
}

object Scheduler {
    fun schedule(context: Context, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = broadcast(context, "alarm:${alarm.id}")
        val trigger = alarm.trigger() ?: return manager.cancel(operation)
        val show =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).putExtra("tab", Tab.Alarm.name),
                PendingIntent.FLAG_IMMUTABLE
            )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), operation)
    }

    fun cancel(context: Context, alarm: Alarm) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(broadcast(context, "alarm:${alarm.id}"))
    }

    fun rescheduleAll(context: Context) {
        Store.alarms.value.forEach { schedule(context, it) }
    }

    private fun broadcast(context: Context, uri: String) = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setData(Uri.parse(uri)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val alarm = Store.alarm(uri.schemeSpecificPart.toInt()) ?: return
        Store.saveAlarm(
            alarm.copy(
                snoozedUntil = 0,
                enabled =
                    alarm.enabled && alarm.days.isNotEmpty()
            )
        )
        RingService.start(context, uri)
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        Scheduler.rescheduleAll(context)
}
