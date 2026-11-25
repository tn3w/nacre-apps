package org.nacre.clock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun AlarmScreen() {
    val context = LocalContext.current
    val alarms by Store.alarms.collectAsState()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Alarms",
                        Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    IconButton(onClick = { settings = true }) {
                        Icon(painterResource(R.drawable.ic_more), "Settings")
                    }
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(alarm, expanded == alarm.id) {
                    expanded = if (expanded == alarm.id) null else alarm.id
                }
            }
        }
        if (alarms.isEmpty()) {
            Text(
                "No alarms",
                Modifier.align(Alignment.Center),
                MaterialTheme.colorScheme.outline
            )
        }
        FloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(painterResource(R.drawable.ic_add), "Add alarm")
        }
    }

    if (settings) SettingsDialog(onDismiss = { settings = false })

    if (creating) {
        val now = LocalTime.now()
        TimeDialog(
            now.hour,
            now.minute,
            onDismiss = { creating = false }
        ) { hour, minute ->
            val alarm = Store.newAlarm(hour, minute)
            expanded = alarm.id
            announce(context, alarm)
        }
    }
}

fun announce(context: Context, alarm: Alarm) {
    val minutes =
        ((alarm.trigger() ?: return) - System.currentTimeMillis() + 59_999) / 60_000
    Toast
        .makeText(
            context,
            "Alarm set for ${formatSpan(minutes)} from now",
            Toast.LENGTH_SHORT
        ).show()
}

private fun update(context: Context, alarm: Alarm) {
    Store.saveAlarm(alarm)
    if (alarm.enabled) announce(context, alarm)
}

@Composable
private fun AlarmCard(alarm: Alarm, expanded: Boolean, onExpand: () -> Unit) {
    val context = LocalContext.current
    var editingTime by remember { mutableStateOf(false) }
    val active = alarm.trigger() != null
    val color = with(MaterialTheme.colorScheme) { if (active) onSurface else outline }

    Card(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LocalTime.of(alarm.hour, alarm.minute).format(timeFormatter()),
                    modifier = Modifier.weight(1f).clickable { editingTime = true },
                    color = color,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light
                )
                Switch(
                    checked = active,
                    onCheckedChange = {
                        update(
                            context,
                            alarm.copy(enabled = it, snoozedUntil = 0, skipped = 0)
                        )
                    }
                )
            }
            Text(
                summary(alarm, timeFormatter()),
                color = MaterialTheme.colorScheme.outline
            )
            if (expanded) AlarmDetails(alarm)
        }
    }

    if (editingTime) {
        TimeDialog(alarm.hour, alarm.minute, onDismiss = {
            editingTime = false
        }) { hour, minute ->
            val edited = alarm.copy(hour = hour, minute = minute, enabled = true)
            update(context, edited.copy(snoozedUntil = 0, skipped = 0))
        }
    }
}

@Composable
private fun AlarmDetails(alarm: Alarm) {
    val context = LocalContext.current
    var label by remember(alarm.id) { mutableStateOf(alarm.label) }
    val title = remember(alarm.ringtone) { ringtoneTitle(context, alarm.ringtone) }
    val picker =
        rememberLauncherForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode !=
                Activity.RESULT_OK
            ) {
                return@rememberLauncherForActivityResult
            }
            val uri =
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            val ringtone = uri?.takeIf { it != Settings.System.DEFAULT_ALARM_ALERT_URI }
            Store.saveAlarm(alarm.copy(ringtone = ringtone?.toString()))
        }

    Column(
        Modifier.padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DayPicker(alarm.days) { update(context, alarm.copy(days = it, enabled = true)) }
        OutlinedTextField(
            value = label,
            onValueChange = {
                label = it
                Store.saveAlarm(alarm.copy(label = it))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Label") },
            singleLine = true
        )
        Setting("Sound", { picker.launch(ringtonePicker(alarm.ringtone)) }) {
            Text(title, color = MaterialTheme.colorScheme.outline)
        }
        Setting("Vibrate", { Store.saveAlarm(alarm.copy(vibrate = !alarm.vibrate)) }) {
            Switch(alarm.vibrate, { Store.saveAlarm(alarm.copy(vibrate = it)) })
        }
        TextButton(onClick = { Store.deleteAlarm(alarm) }) {
            Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete")
        }
    }
}

@Composable
fun Setting(title: String, onClick: () -> Unit, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value()
    }
}

@Composable
private fun DayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        orderedDays().forEach { day ->
            val selected = day in days
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.surface)
                    .border(
                        1.dp,
                        if (selected) colors.primary else colors.outline,
                        CircleShape
                    ).clickable { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    color = if (selected) colors.onPrimary else colors.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(hour, minute, is24Hour)

    Dialog(onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TimePicker(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onConfirm(state.hour, state.minute)
                            onDismiss()
                        }
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

private fun orderedDays(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return List(7) { first.plus(it.toLong()) }
}

private fun summary(alarm: Alarm, formatter: DateTimeFormatter): String {
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val snoozed = alarm.snoozedUntil.takeIf { it > now }?.let {
        "Snoozed until " + Instant.ofEpochMilli(it).atZone(zone).format(formatter)
    }
    val skipping = alarm.skipped.takeIf { it > now }?.let {
        val day = Instant.ofEpochMilli(it).atZone(zone).dayOfWeek
        "Skipping " + day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
    return listOfNotNull(
        alarm.label.ifBlank {
            null
        },
        snoozed ?: repeatText(alarm),
        skipping
    )
        .joinToString(" · ")
}

private fun repeatText(alarm: Alarm): String {
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return when {
        alarm.days.isEmpty() && !alarm.enabled -> {
            "Not scheduled"
        }

        alarm.days.isEmpty() -> {
            if (alarm.next().toLocalDate() ==
                LocalDate.now()
            ) {
                "Today"
            } else {
                "Tomorrow"
            }
        }

        alarm.days.size == 7 -> {
            "Every day"
        }

        alarm.days == DayOfWeek.entries.toSet() - weekend -> {
            "Weekdays"
        }

        alarm.days == weekend -> {
            "Weekends"
        }

        else -> {
            orderedDays()
                .filter { it in alarm.days }
                .joinToString(
                    ", "
                ) { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
    }
}

private fun ringtonePicker(ringtone: String?) =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        .putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            Settings.System.DEFAULT_ALARM_ALERT_URI
        ).putExtra(
            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            ringtone?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        )

private fun ringtoneTitle(context: Context, ringtone: String?): String {
    val uri = ringtone?.let(Uri::parse) ?: return "Default"
    return RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Default"
}
