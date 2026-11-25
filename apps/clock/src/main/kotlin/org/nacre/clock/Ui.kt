package org.nacre.clock

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun rememberNow(period: Long = 1000): State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(period - value % period)
        }
    }

@Composable
fun timeFormatter(): DateTimeFormatter {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(is24Hour) {
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a")
    }
}

fun dateFormatter(skeleton: String): DateTimeFormatter = DateTimeFormatter.ofPattern(
    DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
)

fun formatSpan(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (hours == 0L) return "$minutes min"
    return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
}

fun formatClock(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    if (hours > 0) return "%d:%02d:%02d".format(hours, minutes, seconds)
    return "%d:%02d".format(minutes, seconds)
}
