package org.nacre.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

private const val UPCOMING_WINDOW = 2 * 60 * 60 * 1000L

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val ringtone: String? = null,
    val snoozedUntil: Long = 0,
    val skipped: Long = 0
) {
    fun next(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        val today = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
        return (0..14L).map { today.plusDays(it) }.first {
            it.isAfter(now) &&
                (days.isEmpty() || it.dayOfWeek in days) &&
                it.toInstant().toEpochMilli() != skipped
        }
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
        .put("skipped", skipped)

    companion object {
        const val UPCOMING_CHANNEL = "upcoming"

        fun fromJson(json: JSONObject): Alarm {
            val mask = json.getInt("days")
            return Alarm(
                id = json.getInt("id"),
                hour = json.getInt("hour"),
                minute = json.getInt("minute"),
                days = DayOfWeek.entries.filter {
                    mask shr it.ordinal and 1 == 1
                }.toSet(),
                label = json.getString("label"),
                enabled = json.getBoolean("enabled"),
                vibrate = json.getBoolean("vibrate"),
                ringtone = json.optString("ringtone").ifEmpty { null },
                snoozedUntil = json.getLong("snoozedUntil"),
                skipped = json.optLong("skipped")
            )
        }
    }
}

object Scheduler {
    fun schedule(context: Context, alarm: Alarm) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = broadcast(context, "alarm:${alarm.id}")
        val upcoming = broadcast(context, "upcoming:${alarm.id}")
        context.getSystemService(
            NotificationManager::class.java
        ).cancel("upcoming", alarm.id)

        val trigger = alarm.trigger()
        if (trigger == null) {
            manager.cancel(operation)
            manager.cancel(upcoming)
            return
        }
        val show = openTab(context, Tab.Alarm)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), operation)
        manager.set(AlarmManager.RTC, trigger - UPCOMING_WINDOW, upcoming)
    }

    fun schedule(context: Context, timer: Timer) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = broadcast(context, "timer:${timer.id}")
        if (!timer.running) return manager.cancel(operation)
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            timer.endsAt,
            operation
        )
    }

    fun cancel(context: Context, uri: String) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, uri))
    }

    fun rescheduleAll(context: Context) {
        Store.alarms.value.forEach { schedule(context, it) }
        Store.timers.value.forEach { schedule(context, it) }
    }

    fun broadcast(context: Context, uri: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setData(Uri.parse(uri)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val id = uri.schemeSpecificPart.toInt()
        when (uri.scheme) {
            "alarm" -> {
                val alarm = Store.alarm(id) ?: return
                val enabled = alarm.enabled && alarm.days.isNotEmpty()
                Store.saveAlarm(alarm.copy(snoozedUntil = 0, enabled = enabled))
                RingService.start(context, uri)
            }

            "timer" -> {
                val timer = Store.timer(id)?.takeIf { it.running } ?: return
                notifyTimer(context, timer)
                RingService.start(context, uri)
            }

            "upcoming" -> Store.alarm(id)?.let { notifyUpcoming(context, it) }

            "skip" -> Store.skip(id)
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        Scheduler.rescheduleAll(context)
}

private fun notifyUpcoming(context: Context, alarm: Alarm) {
    val trigger = alarm.trigger() ?: return
    val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
    val time = Instant.ofEpochMilli(trigger).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))
    val dismiss = Notification.Action.Builder(
        Icon.createWithResource(context, R.drawable.ic_alarm),
        "Dismiss",
        Scheduler.broadcast(context, "skip:${alarm.id}")
    ).build()

    val notification = Notification.Builder(context, Alarm.UPCOMING_CHANNEL)
        .setSmallIcon(R.drawable.ic_alarm)
        .setContentTitle("Upcoming alarm")
        .setContentText(
            listOf(time, alarm.label).filter {
                it.isNotBlank()
            }.joinToString(" · ")
        )
        .setContentIntent(openTab(context, Tab.Alarm))
        .addAction(dismiss)
        .build()
    context.getSystemService(NotificationManager::class.java)
        .notify("upcoming", alarm.id, notification)
}
