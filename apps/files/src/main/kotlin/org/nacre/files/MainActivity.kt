package org.nacre.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nacre.core.NacreTheme

enum class Tab(val icon: Int) {
    Browse(R.drawable.ic_folder),
    Recent(R.drawable.ic_recent),
    Starred(R.drawable.ic_star),
    Trash(R.drawable.ic_delete)
}

class Clip(val files: List<File>, val cut: Boolean)

sealed interface Dialog {
    data class Name(val title: String, val initial: String, val done: (String) -> Unit) :
        Dialog

    data class Confirm(val text: String, val action: String, val done: () -> Unit) :
        Dialog

    data class Properties(val files: List<File>) : Dialog

    data object Sorting : Dialog
}

class MainActivity : ComponentActivity() {
    private val tab = mutableStateOf(Tab.Browse)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        Store.init(applicationContext)
        val saved = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab")
        Tab.entries.find { it.name == saved }?.let { tab.value = it }
        val external = intent.data?.takeIf {
            granted(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val writable = granted(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        setContent {
            NacreTheme {
                Surface {
                    if (external != null) {
                        Viewer(
                            uri = external,
                            name = displayName(external),
                            type = type(external),
                            writable = writable,
                            onClose = ::finish
                        )
                    } else {
                        Gated { FilesApp(tab) }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }

    private fun granted(flag: Int) = intent.action in
        setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT) &&
        intent.data?.scheme == "content" &&
        intent.flags and flag != 0

    private fun type(uri: Uri) = intent.type ?: contentResolver.getType(uri) ?: ""

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "Text"
}

@Composable
fun Viewer(uri: Uri, name: String, type: String, writable: Boolean, onClose: () -> Unit) {
    if (type == "application/pdf") {
        PdfViewer(uri, name, onClose)
    } else {
        Editor(uri, name, writable, onClose)
    }
}

@Composable
fun Gated(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        granted = Environment.isExternalStorageManager()
        onPauseOrDispose {}
    }
    if (granted) return content()

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(R.drawable.ic_folder), null)
        Text(
            "Files needs access to manage all files on this device.",
            textAlign = TextAlign.Center
        )
        Button({
            val uri = Uri.parse("package:${context.packageName}")
            context.startActivity(
                Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
            )
        }) { Text("Allow access") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesApp(tabState: MutableState<Tab>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Store.settings.collectAsState()
    val starred by Store.starred.collectAsState()
    val trash by Store.trash.collectAsState()
    var tab by tabState
    var path by rememberSaveable { mutableStateOf(Disk.root.path) }
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(emptySet<File>()) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val folder = File(path)
    val plain = tab == Tab.Browse && category == null && query == null

    LifecycleResumeEffect(Unit) {
        version++
        onPauseOrDispose {}
    }
    LaunchedEffect(tab, path, category) { selection = emptySet() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { purge() } }

    val entries by produceState<List<Entry>?>(
        null,
        tab,
        path,
        category,
        query,
        settings,
        version,
        starred,
        trash
    ) {
        if (!query.isNullOrBlank()) delay(250)
        value =
            withContext(Dispatchers.IO) {
                load(context, tab, folder, category, query, settings, starred, trash)
            }
    }

    fun work(block: () -> List<File>) {
        busy = true
        selection = emptySet()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            result.onSuccess { Disk.scan(context, it) }.onFailure {
                Toast.makeText(context, it.message ?: "Failed", Toast.LENGTH_SHORT).show()
            }
            busy = false
            version++
        }
    }

    fun browse(target: File) {
        tab = Tab.Browse
        path = target.path
        category = null
        query = null
    }

    fun open(entry: Entry) = when {
        entry.folder -> browse(entry.file)
        Disk.isText(entry.file) || Disk.isPdf(entry.file) -> viewing = entry.file
        else -> Disk.open(context, entry.file)
    }

    fun create(directory: Boolean) {
        val title = if (directory) "New folder" else "New file"
        dialog =
            Dialog.Name(title, if (directory) "" else ".txt") { name ->
                work { listOf(Disk.create(folder, name, directory)) }
            }
    }

    fun paste(current: Clip) {
        clip = null
        val target = folder
        work {
            val pasted =
                current.files.map {
                    if (current.cut) Disk.move(it, target) else Disk.copy(it, target)
                }
            pasted + if (current.cut) current.files else emptyList()
        }
    }

    val actions =
        Actions(
            context = context,
            selection = selection,
            entries = entries.orEmpty(),
            starred = starred,
            trash = trash,
            onSelect = { selection = it },
            onClip = {
                clip = it
                selection = emptySet()
            },
            onDialog = { dialog = it },
            work = ::work
        )

    viewing?.let { file ->
        Viewer(Uri.fromFile(file), file.name, Disk.mime(file), writable = true) {
            viewing = null
            version++
        }
        return
    }

    BackHandler(selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(
        selection.isEmpty() && tab == Tab.Browse && (!plain || folder != Disk.root)
    ) {
        when {
            query != null -> query = null
            category != null -> category = null
            else -> path = folder.parent ?: Disk.root.path
        }
    }

    when (val current = dialog) {
        null -> {}

        is Dialog.Name -> {
            NameDialog(current.title, current.initial, { dialog = null }) {
                dialog = null
                current.done(it)
            }
        }

        is Dialog.Confirm -> {
            ConfirmDialog(current.text, current.action, { dialog = null }) {
                current.done()
            }
        }

        is Dialog.Properties -> {
            PropertiesDialog(current.files) { dialog = null }
        }

        Dialog.Sorting -> {
            SortDialog(settings) { dialog = null }
        }
    }

    Scaffold(
        topBar = {
            when {
                selection.isNotEmpty() -> {
                    actions.Bar(tab)
                }

                tab == Tab.Browse -> {
                    BrowseBar(
                        title = category?.name ?: Disk.name(folder),
                        back = !plain || folder != Disk.root,
                        query = query,
                        settings = settings,
                        onQuery = { query = it },
                        onBack = {
                            when {
                                query != null -> query = null
                                category != null -> category = null
                                else -> path = folder.parent ?: Disk.root.path
                            }
                        },
                        onCreate = ::create,
                        onSort = { dialog = Dialog.Sorting }
                    )
                }

                else -> {
                    TopAppBar(
                        title = { Text(tab.name) },
                        actions = {
                            if (tab == Tab.Trash && trash.isNotEmpty()) {
                                TextButton({ actions.emptyTrash() }) { Text("Empty") }
                            }
                        }
                    )
                }
            }
        },
        bottomBar = {
            Column {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val current = clip
                if (current != null && plain) {
                    PasteBar(
                        current,
                        onCancel = { clip = null },
                        onPaste = { paste(current) }
                    )
                }
                NavigationBar {
                    Tab.entries.forEach {
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(painterResource(it.icon), null) },
                            label = { Text(it.name) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (plain && selection.isEmpty() && clip == null) {
                FloatingActionButton({ create(directory = true) }) {
                    Icon(painterResource(R.drawable.ic_new_folder), "New folder")
                }
            }
        }
    ) { padding ->
        Listing(
            entries = entries,
            selection = selection,
            starred = starred,
            empty =
                when (tab) {
                    Tab.Browse -> if (query != null) "No results" else "Nothing here"
                    Tab.Recent -> "No recent files"
                    Tab.Starred -> "Long-press a file and star it to find it here"
                    Tab.Trash -> "Trash is empty. Items are deleted after 30 days."
                },
            padding = padding,
            onSelect = { selection = it },
            onOpen = {
                when (tab) {
                    Tab.Trash -> selection = setOf(it.file)
                    else -> open(it)
                }
            },
            detail = { entry ->
                if (tab == Tab.Browse &&
                    plain
                ) {
                    null
                } else {
                    entry.file.parentFile?.let(Disk::name)
                }
            },
            header = {
                if (plain && folder == Disk.root) {
                    item {
                        StorageCard { picked ->
                            if (picked ==
                                Category.Downloads
                            ) {
                                browse(Disk.downloads)
                            } else {
                                category =
                                    picked
                            }
                        }
                    }
                }
                if (plain &&
                    folder != Disk.root
                ) {
                    item { Breadcrumbs(folder) { path = it.path } }
                }
            }
        )
    }
}

private fun purge() {
    val (expired, kept) =
        Store.trash.value.partition {
            it.expired ||
                !File(it.path).exists()
        }
    expired.forEach { File(it.path).deleteRecursively() }
    if (expired.isNotEmpty()) Store.updateTrash { kept }
}

private fun load(
    context: android.content.Context,
    tab: Tab,
    folder: File,
    category: Category?,
    query: String?,
    settings: Settings,
    starred: Set<String>,
    trash: List<Trashed>
): List<Entry> = when (tab) {
    Tab.Browse -> {
        when {
            !query.isNullOrBlank() -> Disk.search(folder, query, settings)
            category != null -> Disk.category(context, category, settings)
            else -> Disk.list(folder, settings)
        }
    }

    Tab.Recent -> {
        Disk.recent(context, settings)
    }

    Tab.Starred -> {
        starred
            .map(::File)
            .filter { it.exists() }
            .map { Disk.entry(it, settings.hidden) }
            .sortedWith(Disk.order(settings))
    }

    Tab.Trash -> {
        trash
            .sortedByDescending { it.deleted }
            .map { File(it.path) }
            .filter { it.exists() }
            .map { Disk.entry(it, true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseBar(
    title: String,
    back: Boolean,
    query: String?,
    settings: Settings,
    onQuery: (String?) -> Unit,
    onBack: () -> Unit,
    onCreate: (Boolean) -> Unit,
    onSort: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(query == null) { if (query != null) focus.requestFocus() }
    TopAppBar(
        title = {
            if (query == null) return@TopAppBar Text(title, maxLines = 1)
            TextField(
                modifier = Modifier.focusRequester(focus),
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search in $title") },
                singleLine = true,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
            )
        },
        navigationIcon = {
            if (back) {
                IconButton(onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
            }
        },
        actions = {
            if (query == null) {
                IconButton({ onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_search), "Search")
                }
            }
            IconButton(
                { menu = true }
            ) { Icon(painterResource(R.drawable.ic_more), "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text("New folder") },
                    onClick = {
                        menu = false
                        onCreate(true)
                    }
                )
                DropdownMenuItem(
                    text = { Text("New file") },
                    onClick = {
                        menu = false
                        onCreate(false)
                    }
                )
                DropdownMenuItem(text = { Text("Sort by") }, onClick = {
                    menu = false
                    onSort()
                })
                DropdownMenuItem(
                    text = { Text("Show hidden files") },
                    trailingIcon = { Checkbox(settings.hidden, null) },
                    onClick = { Store.update { copy(hidden = !hidden) } }
                )
            }
        }
    )
}

@Composable
private fun Breadcrumbs(folder: File, onPick: (File) -> Unit) {
    val trail =
        generateSequence(folder) { it.parentFile }
            .takeWhile { it.path.startsWith(Disk.root.path) }
            .toList()
            .reversed()
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        trail.forEachIndexed { index, part ->
            if (index > 0) Text("›", color = MaterialTheme.colorScheme.outline)
            TextButton({ onPick(part) }, enabled = part != folder) {
                Text(Disk.name(part), maxLines = 1)
            }
        }
    }
}

@Composable
private fun PasteBar(clip: Clip, onCancel: () -> Unit, onPaste: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${if (clip.cut) "Move" else "Copy"} ${items(clip.files.size)}",
            Modifier.weight(1f)
        )
        TextButton(onCancel) { Text("Cancel") }
        Button(onPaste) { Text(if (clip.cut) "Move here" else "Paste here") }
    }
}
