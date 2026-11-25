package org.nacre.clock

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TimerScreen() {
    val timers by Store.timers.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }

    if (timers.isEmpty() || adding) {
        BackHandler(adding && timers.isNotEmpty()) { adding = false }
        TimerKeypad {
            Store.newTimer(it)
            adding = false
        }
        return
    }

    val now by rememberNow(100, SystemClock::elapsedRealtime)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(timers, key = { it.id }) { TimerCard(it, now) }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(painterResource(R.drawable.ic_add), "Add timer")
        }
    }
}

@Composable
private fun TimerKeypad(onStart: (Long) -> Unit) {
    var digits by rememberSaveable { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val (hours, minutes, seconds) = padded.chunked(2).map(String::toLong)
    val duration = (hours * 3600 + minutes * 60 + seconds) * 1000

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            listOf("h", "m", "s").forEachIndexed { index, unit ->
                val entered = digits.length > 4 - index * 2
                val color =
                    with(MaterialTheme.colorScheme) { if (entered) primary else outline }
                Text(
                    padded.substring(index * 2, index * 2 + 2),
                    color = color,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    unit,
                    Modifier.padding(start = 2.dp, end = 12.dp, bottom = 10.dp),
                    color,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "").chunked(
            3
        ).forEach {
            Row {
                it.forEach { key ->
                    Key(key, onDelete = { digits = digits.dropLast(1) }) {
                        val next = (digits + key).trimStart('0')
                        if (next.length <= 6) digits = next
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        FloatingActionButton(
            onClick = { if (duration > 0) onStart(duration) },
            modifier = Modifier.size(72.dp)
        ) {
            Icon(painterResource(R.drawable.ic_play), "Start")
        }
    }
}

@Composable
private fun Key(key: String, onDelete: () -> Unit, onType: () -> Unit) {
    val size = Modifier.size(width = 96.dp, height = 72.dp)
    if (key.isEmpty()) {
        IconButton(onClick = onDelete, modifier = size) {
            Icon(painterResource(R.drawable.ic_backspace), "Delete")
        }
        return
    }
    TextButton(onClick = onType, modifier = size) {
        Text(key, fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TimerCard(timer: Timer, now: Long) {
    val left = timer.left(now)
    val expired = left <= 0
    val paused = !timer.running && timer.remaining != timer.duration
    val time = if (expired) {
        "-${formatClock(-left / 1000)}"
    } else {
        formatClock((left + 999) / 1000)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabelField(timer)
                    Text(time, fontSize = 40.sp, fontWeight = FontWeight.Light)
                }
                if (paused) {
                    IconButton(onClick = { Store.saveTimer(timer.reset()) }) {
                        Icon(painterResource(R.drawable.ic_stop), "Reset")
                    }
                } else if (timer.running) {
                    TextButton(onClick = { Store.saveTimer(timer.plusMinute()) }) {
                        Text("+1:00")
                    }
                }
                IconButton(onClick = { Store.deleteTimer(timer) }) {
                    Icon(painterResource(R.drawable.ic_delete), "Delete")
                }
                FilledIconButton(
                    onClick = {
                        Store.saveTimer(if (expired) timer.reset() else timer.toggled())
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    val icon = when {
                        expired -> R.drawable.ic_stop
                        timer.running -> R.drawable.ic_pause
                        else -> R.drawable.ic_play
                    }
                    Icon(painterResource(icon), "Start or pause")
                }
            }
            LinearProgressIndicator(
                progress = { (left.toFloat() / timer.duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun LabelField(timer: Timer) {
    var label by remember(timer.id) { mutableStateOf(timer.label) }
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleMedium

    BasicTextField(
        value = label,
        onValueChange = {
            label = it
            Store.saveTimer(timer.copy(label = it))
        },
        singleLine = true,
        textStyle = style.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Box {
                if (label.isEmpty()) Text("Label", color = colors.outline, style = style)
                field()
            }
        }
    )
}
