package org.nacre.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.net.toUri
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

private const val UPCOMING_WINDOW = 2 * 60 * 60 * 1000L
private const val LATE_WINDOW = 10 * 60 * 1000L

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
    val skipped: Long = 0,
    val scheduled: Long = 0
) {
    init {
        require(hour in 0..23 && minute in 0..59)
    }

    fun next(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime = (0..14L)
        .map { now.toLocalDate().plusDays(it).atTime(hour, minute).atZone(now.zone) }
        .first {
            it.isAfter(now) &&
                (days.isEmpty() || it.dayOfWeek in days) &&
                it.toInstant().toEpochMilli() != skipped
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
        .put("scheduled", scheduled)

    companion object {
        const val UPCOMING_CHANNEL = "upcoming"
        const val MISSED_CHANNEL = "missed"

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
                skipped = json.optLong("skipped"),
                scheduled = json.optLong("scheduled")
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
        if (!timer.running || timer.left() <= 0) return manager.cancel(operation)
        manager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            timer.endsAt,
            operation
        )
    }

    fun cancel(context: Context, uri: String) {
        context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, uri))
    }

    fun rescheduleAll(context: Context, late: Boolean) {
        val now = System.currentTimeMillis()
        Store.alarms.value.forEach { alarm ->
            val overdue = now - alarm.scheduled
            when {
                alarm.scheduled == 0L || overdue < 0 -> schedule(context, alarm)
                overdue > LATE_WINDOW -> miss(context, alarm)
                late -> ring(context, alarm)
            }
        }
        Store.timers.value.forEach {
            schedule(context, it)
            notifyTimer(context, it)
        }
        notifyStopwatch(context, Store.stopwatch.value)
    }

    fun broadcast(context: Context, uri: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setData(uri.toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val id = uri.schemeSpecificPart.toIntOrNull() ?: return
        when (uri.scheme) {
            "alarm" -> Store.alarm(id)?.let { ring(context, it) }

            "timer" -> {
                val timer = Store.timer(id)?.takeIf { it.running } ?: return
                notifyTimer(context, timer)
                RingService.start(context, uri)
            }

            "upcoming" -> Store.alarm(id)?.let { notifyUpcoming(context, it) }

            "skip" -> Store.skip(id)

            "timer-toggle" -> Store.timer(id)?.let { Store.saveTimer(it.toggled()) }

            "timer-add" -> Store.addMinute(id)

            "timer-reset" -> Store.resetTimer(id)

            "stopwatch-toggle" -> Store.saveStopwatch(Store.stopwatch.value.toggled())

            "stopwatch-lap" -> Store.saveStopwatch(Store.stopwatch.value.lap())

            "stopwatch-reset" -> Store.saveStopwatch(Stopwatch())
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        Scheduler.rescheduleAll(context, late = true)
}

private fun ring(context: Context, alarm: Alarm) {
    Store.advance(alarm)
    RingService.start(context, "alarm:${alarm.id}".toUri())
}

private fun miss(context: Context, alarm: Alarm) {
    Store.advance(alarm)
    notifyAlarm(context, alarm, alarm.scheduled, "Missed alarm", Alarm.MISSED_CHANNEL)
}

private fun notifyUpcoming(context: Context, alarm: Alarm) {
    val trigger = alarm.trigger() ?: return
    notifyAlarm(context, alarm, trigger, "Upcoming alarm", Alarm.UPCOMING_CHANNEL)
}

private fun notifyAlarm(
    context: Context,
    alarm: Alarm,
    time: Long,
    title: String,
    channel: String
) {
    val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
    val text = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))
    val builder = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_alarm)
        .setContentTitle(title)
        .setContentText(
            listOf(text, alarm.label).filter(String::isNotBlank).joinToString(" · ")
        )
        .setContentIntent(openTab(context, Tab.Alarm))
        .setAutoCancel(true)
    if (channel == Alarm.UPCOMING_CHANNEL) {
        builder.addAction(quickAction(context, "Dismiss", "skip:${alarm.id}"))
    }
    context.getSystemService(NotificationManager::class.java)
        .notify(channel, alarm.id, builder.build())
}
