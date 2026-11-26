package org.nacre.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LIMIT = 2 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(uri: Uri, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    val resolver = context.contentResolver

    LaunchedEffect(uri) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)!!.use { stream ->
                        val bytes = stream.readNBytes(LIMIT + 1)
                        check(bytes.size <= LIMIT) { "File is too large to edit" }
                        String(bytes)
                    }
                }
            }
        loaded
            .onSuccess {
                saved = it
                text = it
            }.onFailure {
                Toast
                    .makeText(
                        context,
                        it.message ?: "Can't open $name",
                        Toast.LENGTH_SHORT
                    ).show()
                onClose()
            }
    }

    fun save() = scope.launch {
        val content = text
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    resolver
                        .openOutputStream(
                            uri,
                            "wt"
                        )!!
                        .use { it.write(content.toByteArray()) }
                }
            }
        if (result.isSuccess) saved = content
        val message = if (result.isSuccess) "Saved" else "Can't save $name"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val changed = saved != null && text != saved
    BackHandler { if (changed) discard = true else onClose() }

    if (discard) {
        ConfirmDialog("Discard unsaved changes?", "Discard", { discard = false }, onClose)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (changed) "$name •" else name, maxLines = 1) },
                navigationIcon = {
                    IconButton({ if (changed) discard = true else onClose() }) {
                        Icon(painterResource(R.drawable.ic_back), "Back")
                    }
                },
                actions = {
                    IconButton({ save() }, enabled = changed) {
                        Icon(painterResource(R.drawable.ic_check), "Save")
                    }
                }
            )
        }
    ) { padding ->
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = saved != null,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier =
                Modifier
                    .padding(padding)
                    .imePadding()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
        )
    }
}
