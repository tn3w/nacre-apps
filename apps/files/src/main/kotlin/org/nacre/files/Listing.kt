package org.nacre.files

import android.media.ThumbnailUtils
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun iconOf(entry: Entry): Int {
    val mime = entry.mime
    return when {
        entry.folder -> R.drawable.ic_folder
        mime.startsWith("image/") -> R.drawable.ic_image
        mime.startsWith("video/") -> R.drawable.ic_video
        mime.startsWith("audio/") -> R.drawable.ic_audio
        entry.file.extension.lowercase() == "apk" -> R.drawable.ic_apk
        Disk.isZip(entry.file) || "compressed" in mime -> R.drawable.ic_archive
        mime.startsWith("text/") || mime == "application/pdf" -> R.drawable.ic_document
        else -> R.drawable.ic_file
    }
}

fun items(count: Int) = "$count item${if (count == 1) "" else "s"}"

fun date(time: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

@Composable
fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
fun Thumbnail(entry: Entry) {
    val mime = entry.mime
    val bitmap by produceState<ImageBitmap?>(null, entry.file, entry.modified) {
        val image = mime.startsWith("image/")
        if (!image && !mime.startsWith("video/")) return@produceState
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val size = Size(128, 128)
                    if (image) {
                        ThumbnailUtils.createImageThumbnail(entry.file, size, null)
                    } else {
                        ThumbnailUtils.createVideoThumbnail(entry.file, size, null)
                    }
                }.getOrNull()?.asImageBitmap()
            }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(painterResource(iconOf(entry)), null)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: Entry,
    selected: Boolean,
    starred: Boolean,
    detail: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceVariant else colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(
                        44.dp
                    ).clip(RoundedCornerShape(12.dp))
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_check), null, tint = colors.onPrimary)
            }
        } else {
            Thumbnail(entry)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val amount =
                if (entry.folder) {
                    items(entry.count)
                } else {
                    size(entry.size)
                }
            Text(
                listOfNotNull(amount, date(entry.modified), detail).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (starred) Icon(painterResource(R.drawable.ic_star), null, Modifier.size(18.dp))
    }
}

@Composable
fun Listing(
    entries: List<Entry>?,
    selection: Set<File>,
    starred: Set<String>,
    empty: String,
    padding: PaddingValues,
    onSelect: (Set<File>) -> Unit,
    onOpen: (Entry) -> Unit,
    detail: (Entry) -> String? = { null },
    header: LazyListScope.() -> Unit = {}
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        header()
        when {
            entries == null -> {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            entries.isEmpty() -> {
                item {
                    Text(
                        empty,
                        Modifier.fillMaxWidth().padding(48.dp),
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }

            else -> {
                items(entries, key = { it.file.path }) { entry ->
                    val selected = entry.file in selection

                    fun toggle() = onSelect(
                        if (selected) {
                            selection - entry.file
                        } else {
                            selection +
                                entry.file
                        }
                    )
                    FileRow(
                        entry = entry,
                        selected = selected,
                        starred = entry.file.path in starred,
                        detail = detail(entry),
                        onClick = {
                            if (selection.isEmpty()) {
                                onOpen(
                                    entry
                                )
                            } else {
                                toggle()
                            }
                        },
                        onLongClick = ::toggle
                    )
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
fun StorageCard(onCategory: (Category) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val space by produceState(0L to 1L) {
        value =
            withContext(Dispatchers.IO) { Disk.space() }
    }
    val (used, total) = space
    Column(
        Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceVariant)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { used.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = colors.primary,
            trackColor = colors.surface,
            drawStopIndicator = {}
        )
        Text(
            "${size(used)} used of ${size(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.outline
        )
    }
    Category.entries.chunked(4).forEach { row ->
        Row(Modifier.padding(horizontal = 8.dp)) {
            row.forEach { category ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onCategory(category) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(category.icon), null)
                    }
                    Text(category.name, style = MaterialTheme.typography.labelMedium)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Text(
        "All files",
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = colors.outline
    )
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    val stem = initial.substringBeforeLast('.').ifEmpty { initial }.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank() && '/' !in value.text
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton({ onDone(value.text.trim()) }, enabled = valid) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(
    text: String,
    action: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton({
                onDismiss()
                onConfirm()
            }) { Text(action) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SortDialog(settings: Settings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort by") },
        text = {
            Column {
                Sort.entries.forEach { sort ->
                    Option(sort.label, settings.sort == sort, radio = true) {
                        Store.update { copy(sort = sort) }
                    }
                }
                Option("Descending", settings.descending, radio = false) {
                    Store.update { copy(descending = !descending) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } }
    )
}

@Composable
private fun Option(label: String, checked: Boolean, radio: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(12.dp)
            ).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (radio) RadioButton(checked, onClick) else Checkbox(checked, { onClick() })
        Text(label)
    }
}

@Composable
fun PropertiesDialog(files: List<File>, onDismiss: () -> Unit) {
    val total by produceState<Long?>(null, files) {
        value = withContext(Dispatchers.IO) { files.sumOf { Disk.size(it) } }
    }
    val single = files.singleOrNull()
    val rows =
        buildList {
            if (single != null) {
                add("Name" to single.name)
                add("Location" to (single.parent ?: ""))
                if (single.isFile) add("Type" to Disk.mime(single))
                if (single.isDirectory) {
                    add("Contains" to "${single.list()?.size ?: 0} items")
                }
                add("Modified" to date(single.lastModified()))
            } else {
                add("Selected" to "${files.size} items")
            }
            add("Size" to (total?.let { size(it) } ?: "Calculating…"))
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Text(value, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } }
    )
}
