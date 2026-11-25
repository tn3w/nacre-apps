package org.nacre.clock

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

data class Timer(
    val id: Int,
    val duration: Long,
    val remaining: Long = duration,
    val endsAt: Long = 0,
    val label: String = ""
) {
    val running get() = endsAt > 0

    fun left(now: Long = System.currentTimeMillis()) =
        if (running) endsAt - now else remaining

    fun toggled(now: Long = System.currentTimeMillis()) = if (running) {
        copy(remaining = left(now), endsAt = 0)
    } else {
        copy(endsAt = now + remaining)
    }

    fun reset() = copy(remaining = duration, endsAt = 0)

    fun plusMinute(now: Long = System.currentTimeMillis()) = when {
        !running -> copy(remaining = remaining + 60_000)
        endsAt <= now -> copy(endsAt = now + 60_000)
        else -> copy(endsAt = endsAt + 60_000)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("duration", duration)
        .put("remaining", remaining)
        .put("endsAt", endsAt)
        .put("label", label)

    companion object {
        const val CHANNEL = "timers"

        fun fromJson(json: JSONObject) = Timer(
            id = json.getInt("id"),
            duration = json.getLong("duration"),
            remaining = json.getLong("remaining"),
            endsAt = json.getLong("endsAt"),
            label = json.getString("label")
        )
    }
}

fun notifyTimer(context: Context, timer: Timer) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (timer.left() <= 0 || !timer.running) return manager.cancel("timer", timer.id)

    val notification = Notification.Builder(context, Timer.CHANNEL)
        .setSmallIcon(R.drawable.ic_timer)
        .setContentTitle(timer.label.ifEmpty { "Timer" })
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(timer.endsAt)
        .setShowWhen(true)
        .setOngoing(true)
        .setContentIntent(openTab(context, Tab.Timer))
        .build()
    manager.notify("timer", timer.id, notification)
}
