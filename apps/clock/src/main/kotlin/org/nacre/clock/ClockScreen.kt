package org.nacre.clock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val regions =
    Regex("(Africa|America|Antarctica|Asia|Atlantic|Australia|Europe|Indian|Pacific)/.+")

fun cityName(zone: String) = zone.substringAfterLast('/').replace('_', ' ')

@Composable
fun ClockScreen() {
    val now by rememberNow()
    val zones by Store.zones.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val formatter = timeFormatter()
    val here = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(24.dp, 48.dp, 24.dp, 96.dp)) {
            item {
                Text(
                    here.format(formatter),
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    here.format(dateFormatter("EEEEdMMMM")),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(32.dp))
            }
            items(zones, key = { it }) { ZoneRow(it, here, formatter) }
        }
        FloatingActionButton(
            onClick = { picking = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(painterResource(R.drawable.ic_add), "Add city")
        }
    }

    if (picking) ZonePicker(onDismiss = { picking = false })
}

@Composable
private fun ZoneRow(zone: String, here: ZonedDateTime, formatter: DateTimeFormatter) {
    val there = here.withZoneSameInstant(ZoneId.of(zone))

    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(cityName(zone), style = MaterialTheme.typography.titleLarge)
            Text(
                zoneDifference(here, there),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Text(there.format(formatter), style = MaterialTheme.typography.headlineMedium)
        IconButton(onClick = { Store.removeZone(zone) }) {
            Icon(
                painterResource(R.drawable.ic_close),
                "Remove $zone",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun zoneDifference(here: ZonedDateTime, there: ZonedDateTime): String {
    val day =
        when (ChronoUnit.DAYS.between(here.toLocalDate(), there.toLocalDate())) {
            -1L -> "Yesterday"
            1L -> "Tomorrow"
            else -> "Today"
        }
    val minutes = (there.offset.totalSeconds - here.offset.totalSeconds) / 60L
    if (minutes == 0L) return day
    val direction = if (minutes > 0) "ahead" else "behind"
    return "$day, ${formatSpan(abs(minutes))} $direction"
}

@Composable
private fun ZonePicker(onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val zones =
        remember {
            ZoneId
                .getAvailableZoneIds()
                .filter {
                    it.matches(
                        regions
                    )
                }.sortedBy(::cityName)
        }
    val matches =
        zones.filter {
            it.replace('_', ' ').contains(query.trim(), ignoreCase = true)
        }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp
                            ).focusRequester(focus),
                    placeholder = { Text("Search city") },
                    singleLine = true
                )
                LazyColumn {
                    items(matches, key = { it }) { zone ->
                        ListItem(
                            headlineContent = { Text(cityName(zone)) },
                            supportingContent = { Text(zoneDetails(zone)) },
                            modifier =
                                Modifier.clickable {
                                    Store.addZone(zone)
                                    onDismiss()
                                }
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun zoneDetails(zone: String): String {
    val offset =
        ZoneId
            .of(zone)
            .rules
            .getOffset(Instant.now())
            .id
            .replace("Z", "")
    return "${zone.substringBefore('/')} · GMT$offset"
}
