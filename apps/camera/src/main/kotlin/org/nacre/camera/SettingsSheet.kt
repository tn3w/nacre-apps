package org.nacre.camera

import androidx.camera.core.CameraInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(capabilities: Capabilities, onDismiss: () -> Unit) {
    val settings by Store.settings.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section("Photo")
            Toggle("Grid lines", settings.grid) { Store.update { copy(grid = it) } }
            Toggle("Level", "Shows a horizon line when you hold the phone straight", settings.level) {
                Store.update { copy(level = it) }
            }
            if (!CameraInfo.mustPlayShutterSound()) {
                Toggle("Shutter sound", settings.sound) { Store.update { copy(sound = it) } }
            }
            Toggle("Mirror selfies", "Saves front camera photos as previewed", settings.mirror) {
                Store.update { copy(mirror = it) }
            }
            Toggle(
                "Hold shutter for quick video",
                "Otherwise holding the shutter takes a burst",
                settings.hold == Hold.Video
            ) { Store.update { copy(hold = if (it) Hold.Video else Hold.Burst) } }
            Toggle("Save location", "Adds where photos and videos were taken", settings.location) {
                Store.update { copy(location = it) }
            }
            Toggle("Volume keys take photos", settings.volumeKeys) {
                Store.update { copy(volumeKeys = it) }
            }
            if (capabilities.ultraHdr) {
                Toggle("Ultra HDR", "Brighter highlights on HDR screens", settings.ultraHdr) {
                    Store.update { copy(ultraHdr = it) }
                }
            }
            if (capabilities.raw) {
                Toggle("RAW in Pro mode", "Also saves an uncompressed DNG", settings.raw) {
                    Store.update { copy(raw = it) }
                }
            }

            Section("Video")
            Toggle("Record sound", settings.audio) { Store.update { copy(audio = it) } }
            if (capabilities.stabilization) {
                Toggle("Stabilization", settings.stabilization) {
                    Store.update { copy(stabilization = it) }
                }
            }
            if (capabilities.smooth) {
                Toggle("60 fps", settings.smooth) { Store.update { copy(smooth = it) } }
            }
            if (capabilities.hdrVideo) {
                Toggle("HDR video", "10-bit HLG", settings.hdrVideo) {
                    Store.update { copy(hdrVideo = it) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Toggle(label, null, checked, onChange)

@Composable
private fun Toggle(
    label: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 8.dp)
    )
}
