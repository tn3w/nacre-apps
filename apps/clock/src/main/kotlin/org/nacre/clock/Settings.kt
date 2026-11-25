package org.nacre.clock

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

enum class Option(
    val title: String,
    val values: List<Int>,
    val default: Int,
    val label: (Int) -> String
) {
    Snooze("Snooze length", listOf(5, 10, 15, 20, 30), 10, { "$it min" }),
    Silence("Silence after", listOf(1, 5, 10, 15, 30, 0), 10, {
        if (it == 0) "Never" else "$it min"
    }),
    Ramp("Gradually increase volume", listOf(0, 15, 30, 60), 30, {
        if (it == 0) "Off" else "$it s"
    });

    fun next(value: Int) = values[(values.indexOf(value) + 1) % values.size]
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column {
                Option.entries.forEach { option ->
                    var value by remember { mutableIntStateOf(Store[option]) }
                    Setting(option.title, {
                        value = option.next(value)
                        Store[option] = value
                    }) {
                        Text(
                            option.label(value),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    )
}
