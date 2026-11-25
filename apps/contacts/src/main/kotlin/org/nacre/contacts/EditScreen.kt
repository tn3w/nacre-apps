package org.nacre.contacts

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.MonthDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PHOTO_SIZE = 720

class Form(private val prefill: List<Row>) {
    var loaded by mutableStateOf(false)
    var rawId: Long? = null
    var original = emptyList<Row>()
    var initial = emptyList<Row>()
    val rows = mutableStateListOf<Row>()
    var photo by mutableStateOf<Uri?>(null)
    var photoChanged by mutableStateOf(false)

    val changed get() = photoChanged || rows.toList() != initial

    val valid get() = rows.filter { it.kind == Kind.Birthday && !it.blank }
        .all { validBirthday(it.value.trim()) }

    fun load(resolver: ContentResolver, contactId: Long?) {
        val details = contactId?.let(resolver::details)
        rawId = details?.rawId
        original = rawId?.let(resolver::rawRows).orEmpty()
        photo = details?.photo
        val existing = original + prefill.filter { row ->
            !row.kind.single || original.none { it.kind == row.kind }
        }
        val starters = if (contactId == null) listOf(Kind.Phone, Kind.Email) else emptyList()
        val missing = Kind.entries.filter { kind ->
            (kind.single || kind in starters) && existing.none { it.kind == kind }
        }
        initial = existing + missing.map(::Row)
        rows.addAll(initial)
        loaded = true
    }
}

private fun validBirthday(value: String) = runCatching {
    if (value.startsWith("--")) MonthDay.parse(value) else LocalDate.parse(value)
}.isSuccess

@Composable
fun EditScreen(
    contactId: Long?,
    form: Form,
    onBack: () -> Unit,
    onSaved: (Uri?, Long?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var discarding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(form) {
        if (!form.loaded) withContext(Dispatchers.IO) { form.load(context.contentResolver, contactId) }
    }
    val leave = { if (form.changed) discarding = true else onBack() }
    BackHandler(onBack = leave)

    Page(if (contactId == null) "New contact" else "Edit contact", leave, {
        TextButton(
            onClick = {
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { context.save(form) }.getOrNull()
                    }
                    saving = false
                    if (result == null) {
                        Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
                    } else {
                        onSaved(result.first, result.second)
                    }
                }
            },
            enabled = form.loaded && form.valid && !saving
        ) { Text("Save") }
    }) {
        if (form.loaded) EditFields(form)
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("Discard changes?") },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("Keep editing") } },
            confirmButton = { TextButton(onClick = onBack) { Text("Discard") } }
        )
    }
}

private fun Context.save(form: Form): Pair<Uri?, Long?> {
    val rows = form.rows.map { row ->
        if (row.kind == Kind.Phone && !row.blank) row.copy(value = formatPhone(row.value)) else row
    }
    val rawId = contentResolver.save(form.rawId, form.original, rows)
    if (form.photoChanged) {
        val bytes = form.photo?.let { uri -> contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        contentResolver.setPhoto(rawId, bytes)
    }
    cacheDir.listFiles { file -> file.name.startsWith("photo") }?.forEach(File::delete)
    val uri = contentResolver.lookupUri(rawId)
    return uri to uri?.let(contentResolver::contactId)
}

@Composable
private fun EditFields(form: Form) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val photo = withContext(Dispatchers.IO) { runCatching { context.squarePhoto(uri) } }
            photo.onSuccess {
                form.photo = it
                form.photoChanged = true
            }
        }
    }
    val name = form.rows.find { it.kind == Kind.Name }
    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
            Box(Modifier.clickable {
                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            }) {
                Avatar(name?.value.orEmpty(), form.photo, 112.dp)
            }
            Row {
                TextButton(onClick = {
                    picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                }) { Text(if (form.photo == null) "Add photo" else "Change photo") }
                if (form.photo != null) {
                    TextButton(onClick = {
                        form.photo = null
                        form.photoChanged = true
                    }) { Text("Remove") }
                }
            }
        }
        Kind.entries.forEach { kind -> KindFields(form, kind) }
    }
}

@Composable
private fun KindFields(form: Form, kind: Kind) {
    form.rows.forEachIndexed { index, row ->
        if (row.kind != kind) return@forEachIndexed
        val update = { changed: Row -> form.rows[index] = changed }
        when (kind) {
            Kind.Name -> PairFields(row, "First name", "Last name", update)

            Kind.Organization -> PairFields(row, "Company", "Title", update)

            Kind.Birthday -> Field(
                row.value,
                "Birthday (YYYY-MM-DD)",
                KeyboardType.Number,
                error = row.value.isNotBlank() && !validBirthday(row.value.trim())
            ) { update(row.copy(value = it)) }

            Kind.Nickname -> Field(row.value, kind.name) { update(row.copy(value = it)) }

            Kind.Note -> Field(row.value, kind.name, multiline = true) {
                update(row.copy(value = it))
            }

            else -> ListField(form, index, row)
        }
    }
    if (!kind.single) {
        TextButton(onClick = { form.rows += Row(kind) }) {
            Icon(painterResource(R.drawable.ic_add), null)
            Text("Add ${kind.name.lowercase()}", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PairFields(row: Row, first: String, second: String, update: (Row) -> Unit) {
    Field(row.value, first) { update(row.copy(value = it)) }
    Field(row.extra, second) { update(row.copy(extra = it)) }
}

@Composable
private fun ListField(form: Form, index: Int, row: Row) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val keyboard = when (row.kind) {
        Kind.Phone -> KeyboardType.Phone
        Kind.Email -> KeyboardType.Email
        Kind.Website -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(
            row.value,
            row.kind.name,
            keyboard,
            Modifier.weight(1f),
            multiline = row.kind == Kind.Address
        ) {
            form.rows[index] = row.copy(value = it)
        }
        if (row.kind.types.isNotEmpty()) {
            TextButton(onClick = { menu = true }) {
                Text(row.kind.label(context.resources, row.type, row.label))
                DropdownMenu(menu, { menu = false }) {
                    row.kind.types.forEach { type ->
                        DropdownMenuItem({ Text(row.kind.label(context.resources, type, null)) }, {
                            form.rows[index] = row.copy(type = type)
                            menu = false
                        })
                    }
                }
            }
        }
        IconButton(onClick = { form.rows.removeAt(index) }) {
            Icon(painterResource(R.drawable.ic_close), "Remove")
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
    multiline: Boolean = false,
    error: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        isError = error,
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(
            capitalization = when {
                keyboard != KeyboardType.Text -> KeyboardCapitalization.None
                multiline -> KeyboardCapitalization.Sentences
                else -> KeyboardCapitalization.Words
            },
            keyboardType = keyboard
        )
    )
}

private fun Context.squarePhoto(uri: Uri): Uri {
    val source = ImageDecoder.createSource(contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = PHOTO_SIZE.toFloat() / minOf(info.size.width, info.size.height)
        if (scale < 1) {
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(PHOTO_SIZE),
                (info.size.height * scale).toInt().coerceAtLeast(PHOTO_SIZE)
            )
        }
    }
    val side = minOf(bitmap.width, bitmap.height)
    val square = Bitmap.createBitmap(
        bitmap,
        (bitmap.width - side) / 2,
        (bitmap.height - side) / 2,
        side,
        side
    )
    val bytes = ByteArrayOutputStream().also {
        square.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()
    val file = File(cacheDir, "photo-${System.nanoTime()}.jpg").apply { writeBytes(bytes) }
    return Uri.fromFile(file)
}
