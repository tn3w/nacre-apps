package org.nacre.clock

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

data class Stopwatch(
    val accumulated: Long = 0,
    val startedAt: Long = 0,
    val laps: List<Long> = emptyList()
) {
    val running get() = startedAt > 0

    fun elapsed(now: Long = System.currentTimeMillis()) =
        accumulated + if (running) now - startedAt else 0

    fun toggled(now: Long = System.currentTimeMillis()) = if (running) {
        copy(accumulated = elapsed(now), startedAt = 0)
    } else {
        copy(startedAt = now)
    }

    fun lap(now: Long = System.currentTimeMillis()) = copy(laps = laps + elapsed(now))

    fun toJson(): JSONObject = JSONObject()
        .put("accumulated", accumulated)
        .put("startedAt", startedAt)
        .put("laps", JSONArray(laps))

    companion object {
        fun fromJson(json: JSONObject): Stopwatch {
            val laps = json.getJSONArray("laps")
            return Stopwatch(
                json.getLong("accumulated"),
                json.getLong("startedAt"),
                List(laps.length()) { laps.getLong(it) }
            )
        }
    }
}

fun notifyStopwatch(context: Context, stopwatch: Stopwatch) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (stopwatch.elapsed() == 0L) return manager.cancel("stopwatch", 0)

    val builder = Notification.Builder(context, Timer.CHANNEL)
        .setSmallIcon(R.drawable.ic_stopwatch)
        .setContentTitle("Stopwatch")
        .setOngoing(true)
        .setContentIntent(openTab(context, Tab.Stopwatch))
    if (stopwatch.running) {
        builder.setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - stopwatch.elapsed())
            .setShowWhen(true)
            .addAction(quickAction(context, "Pause", "stopwatch-toggle:0"))
            .addAction(quickAction(context, "Lap", "stopwatch-lap:0"))
    } else {
        builder.setContentText("Paused · ${formatStopwatch(stopwatch.elapsed())}")
            .addAction(quickAction(context, "Resume", "stopwatch-toggle:0"))
            .addAction(quickAction(context, "Reset", "stopwatch-reset:0"))
    }
    manager.notify("stopwatch", 0, builder.build())
}

fun formatStopwatch(milliseconds: Long) =
    formatClock(milliseconds / 1000) + ".%02d".format(milliseconds / 10 % 100)

@Composable
fun StopwatchScreen() {
    val stopwatch by Store.stopwatch.collectAsState()
    val now by rememberNow(if (stopwatch.running) 30 else 1000)
    val elapsed = stopwatch.elapsed(now)

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            formatStopwatch(elapsed),
            Modifier.padding(top = 96.dp, bottom = 48.dp),
            fontSize = 64.sp,
            fontWeight = FontWeight.Light
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val save = Store::saveStopwatch
            if (stopwatch.running) {
                Control(R.drawable.ic_flag, "Lap", primary = false) {
                    save(stopwatch.lap())
                }
            } else if (elapsed > 0) {
                Control(R.drawable.ic_stop, "Reset", primary = false) {
                    save(Stopwatch())
                }
            }
            val icon = if (stopwatch.running) R.drawable.ic_pause else R.drawable.ic_play
            Control(icon, "Start or pause", primary = true) { save(stopwatch.toggled()) }
        }
        LazyColumn(
            Modifier.padding(top = 32.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            val laps = stopwatch.laps
            itemsIndexed(laps.reversed()) { index, total ->
                val number = laps.size - index
                LapRow(number, total - laps.getOrElse(number - 2) { 0 }, total)
            }
        }
    }
}

@Composable
private fun Control(
    icon: Int,
    description: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier.size(72.dp)
    val content = @Composable { Icon(painterResource(icon), description) }
    if (primary) {
        FilledIconButton(onClick, modifier, content = content)
    } else {
        FilledTonalIconButton(onClick, modifier, content = content)
    }
}

@Composable
private fun LapRow(number: Int, split: Long, total: Long) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Lap $number", Modifier.weight(1f), outline)
        Text(formatStopwatch(split), Modifier.weight(1f))
        Text(formatStopwatch(total), color = outline)
    }
}
