package org.nacre.gallery

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.nacre.core.NacreTheme

enum class Tab(val icon: Int) {
    Photos(R.drawable.ic_photos),
    Albums(R.drawable.ic_albums),
    Favorites(R.drawable.ic_favorite),
    Trash(R.drawable.ic_delete)
}

sealed interface Launch {
    data object Browse : Launch

    data class Pick(val types: List<String>, val multiple: Boolean) : Launch

    data class View(val uri: Uri, val mime: String) : Launch

    data class Edit(val uri: Uri, val mime: String) : Launch
}

data class Source(val tab: Tab, val albumId: Long? = null, val query: String = "")

private val PERMISSIONS =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    )

enum class Access { None, Partial, Full }

fun Context.access(): Access {
    fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.Full
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        else -> Access.None
    }
}

class MainActivity : ComponentActivity() {
    private val tab = mutableStateOf(Tab.Photos)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        Store.init(applicationContext)
        val saved = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab")
        Tab.entries.find { it.name == saved }?.let { tab.value = it }
        val launch = launchOf(intent)

        setContent {
            NacreTheme {
                Surface {
                    when (launch) {
                        is Launch.Edit -> Editor(
                            Media.external(launch.uri, launch.mime),
                            ::finishWith,
                            ::finish
                        )

                        is Launch.View -> ViewOnly(launch)

                        else -> Gated { GalleryApp(tab, launch, ::finishWith) }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }

    private fun launchOf(intent: Intent): Launch {
        val data = intent.data
        val content = data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        val mime =
            intent.type
                ?: content?.let {
                    runCatching { contentResolver.getType(it) }.getOrNull()
                }.orEmpty()
        return when (intent.action) {
            Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW -> content?.let {
                Launch.View(it, mime)
            }
                ?: Launch.Browse

            Intent.ACTION_EDIT -> content?.let { Launch.Edit(it, mime) } ?: Launch.Browse

            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> {
                val type = mime.removePrefix("vnd.android.cursor.dir/").ifEmpty { "*/*" }
                val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
                    ?: listOf(if ('/' in type) type else "$type/*")
                Launch.Pick(
                    types,
                    intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                )
            }

            else -> Launch.Browse
        }
    }

    private fun finishWith(uri: Uri) = finishWith(listOf(uri))

    private fun finishWith(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val result = Intent().setData(
            uris[0]
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            result.clipData =
                ClipData.newRawUri(null, uris[0]).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    @Composable
    private fun ViewOnly(launch: Launch.View) {
        LaunchedEffect(Unit) {
            if (access() != Access.None) Library.observe(applicationContext)
        }
        val all by Library.media.collectAsState()
        val settings by Store.settings.collectAsState()
        var editing by remember { mutableStateOf<Media?>(null) }
        val found = Library.find(launch.uri)
        val library = found?.let { all.items(Source(Tab.Photos), settings) }
        val items = when {
            found == null -> listOf(Media.external(launch.uri, launch.mime))
            found in library.orEmpty() -> library.orEmpty()
            else -> listOf(found)
        }
        if (editing == null) Viewer(items, found ?: items[0], { editing = it }, ::finish)
        editing?.let { media ->
            Editor(media, {
                editing = null
                saved(this)
            }) { editing = null }
        }
    }

    @Composable
    private fun Gated(content: @Composable () -> Unit) {
        var access by remember { mutableStateOf(access()) }
        val launcher =
            rememberLauncherForActivityResult(RequestMultiplePermissions()) {
                access = access()
            }
        LifecycleResumeEffect(Unit) {
            access = access()
            if (access != Access.None) Library.observe(applicationContext)
            onPauseOrDispose {}
        }
        LaunchedEffect(Unit) { if (access == Access.None) launcher.launch(PERMISSIONS) }
        if (access != Access.None) return content()
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Gallery needs access to your photos and videos",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            Button({ launcher.launch(PERMISSIONS) }) { Text("Allow access") }
            TextButton({
                openSettings(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }) {
                Text("Open settings")
            }
        }
    }
}

fun saved(context: Context) {
    Toast.makeText(context, "Saved a copy", Toast.LENGTH_SHORT).show()
}

private fun matches(media: Media, types: List<String>) = types.any { type ->
    type == "*/*" || type == media.mime ||
        (type.endsWith("/*") && media.mime.startsWith(type.dropLast(1)))
}

fun List<Media>.items(source: Source, settings: Settings): List<Media> =
    when (source.tab) {
        Tab.Photos -> filterNot { it.trashed }.search(source.query).arranged(settings)

        Tab.Albums -> filter {
            !it.trashed && it.albumId == source.albumId
        }.arranged(settings)

        Tab.Favorites -> filter { !it.trashed && it.favorite }.arranged(settings)

        Tab.Trash -> filter { it.trashed }.sortedBy { it.expires }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryApp(tab: MutableState<Tab>, launch: Launch, onPicked: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val request = rememberRequest()
    val pick = launch as? Launch.Pick
    val all by Library.media.collectAsState()
    val loaded by Library.loaded.collectAsState()
    val settings by Store.settings.collectAsState()
    val media = if (pick == null) all else all.filter { matches(it, pick.types) }
    var album by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(emptySet<Uri>()) }
    var viewing by remember { mutableStateOf<Pair<Source, Media>?>(null) }
    var editing by remember { mutableStateOf<Media?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val tabs = if (pick == null) Tab.entries else listOf(Tab.Photos, Tab.Albums)
    val source = Source(tab.value, album, query.orEmpty())
    val items = media.items(source, settings)
    val chosen = items.filter { it.uri in selected }

    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            query != null -> query = null
            else -> album = null
        }
    }

    BackHandler(selected.isNotEmpty() || query != null || album != null, ::back)
    LaunchedEffect(tab.value, album) { selected = emptySet() }

    fun open(item: Media) {
        if (pick == null) {
            viewing = source to item
        } else if (!pick.multiple) {
            onPicked(listOf(item.uri))
        } else {
            selected =
                if (item.uri in selected) selected - item.uri else selected + item.uri
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val search = query
                    when {
                        selected.isNotEmpty() -> Text("${selected.size} selected")

                        search != null -> {
                            TextField(
                                search,
                                { query = it },
                                placeholder = { Text("Search name, album, month, year") },
                                singleLine = true,
                                colors =
                                    TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                            )
                        }

                        pick != null -> Text(
                            if (pick.multiple) "Select items" else "Select an item"
                        )

                        album != null -> Text(items.firstOrNull()?.album.orEmpty())

                        else -> Text(tab.value.name)
                    }
                },
                navigationIcon = {
                    val back = selected.isNotEmpty() || query != null || album != null
                    if (!back) return@TopAppBar
                    IconButton(::back) {
                        Icon(painterResource(R.drawable.ic_back), "Back")
                    }
                },
                actions = {
                    val uris = chosen.map { it.uri }
                    val clear = { selected = emptySet() }
                    when {
                        pick != null && selected.isNotEmpty() -> {
                            TextButton({ onPicked(uris) }) { Text("Done") }
                        }

                        tab.value == Tab.Trash && selected.isNotEmpty() -> {
                            Action(R.drawable.ic_restore, "Restore") {
                                request(context.trashRequest(uris, false), clear)
                            }
                            Action(R.drawable.ic_delete, "Delete forever") {
                                request(context.deleteRequest(uris), clear)
                            }
                        }

                        selected.isNotEmpty() -> {
                            Action(R.drawable.ic_check, "Select all") {
                                selected = items.map { it.uri }.toSet()
                            }
                            Action(R.drawable.ic_share, "Share") {
                                share(context, chosen)
                            }
                            Action(R.drawable.ic_favorite, "Favorite") {
                                val favorite = !chosen.all { it.favorite }
                                request(context.favoriteRequest(uris, favorite), clear)
                            }
                            Action(R.drawable.ic_folder, "Move to album") {
                                moving = true
                            }
                            Action(R.drawable.ic_delete, "Move to trash") {
                                request(context.trashRequest(uris, true), clear)
                            }
                        }

                        tab.value == Tab.Trash -> {
                            if (items.isNotEmpty()) {
                                TextButton({
                                    request(
                                        context.deleteRequest(
                                            items.map {
                                                it.uri
                                            }
                                        )
                                    ) {}
                                }) { Text("Empty") }
                            }
                        }

                        else -> {
                            if (tab.value == Tab.Photos && query == null) {
                                Action(R.drawable.ic_search, "Search") { query = "" }
                            }
                            Action(R.drawable.ic_sort, "Sort and view") { sheet = true }
                            if (pick == null) MoreMenu()
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selected.isNotEmpty() && pick == null) return@Scaffold
            NavigationBar {
                tabs.forEach {
                    NavigationBarItem(
                        selected = tab.value == it,
                        onClick = {
                            tab.value = it
                            album = null
                            query = null
                        },
                        icon = { Icon(painterResource(it.icon), null) },
                        label = { Text(it.name) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (!loaded) return@Box
            val onSelect = { it: Set<Uri> -> selected = it }
            val onColumns = { it: Int -> Store.update { copy(columns = it) } }
            when {
                tab.value == Tab.Albums && album == null -> {
                    AlbumGrid(media.albums(settings.albumSort)) { album = it.id }
                }

                tab.value == Tab.Trash -> {
                    MediaGrid(
                        listOf(Section(null, items)),
                        settings.columns,
                        selected,
                        "Trash is empty",
                        ::open,
                        onSelect,
                        onColumns,
                        caption = { daysLeft(it.expires) },
                        header = {
                            if (items.isEmpty()) return@MediaGrid
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Items are deleted forever after 30 days",
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    )
                }

                else -> {
                    MediaGrid(
                        items.sections(settings),
                        settings.columns,
                        selected,
                        when {
                            query != null -> "No results"
                            tab.value == Tab.Favorites -> "Tap the heart on a photo"
                            else -> "No photos or videos yet"
                        },
                        ::open,
                        onSelect,
                        onColumns
                    )
                }
            }
        }
    }

    if (sheet) {
        SortSheet(settings, tab.value == Tab.Albums && album == null) {
            sheet =
                false
        }
    }
    if (moving) {
        MoveDialog(chosen) {
            moving = false
            selected = emptySet()
        }
    }
    viewing?.let { (from, start) ->
        if (editing != null) return@let
        Viewer(media.items(from, settings), start, {
            editing = it
            viewing = from to it
        }) { viewing = null }
    }
    editing?.let { item ->
        Editor(item, {
            editing = null
            saved(context)
        }) { editing = null }
    }
}

private fun daysLeft(expires: Long): String {
    val days = (expires - System.currentTimeMillis()) / 86_400_000
    return "${days.coerceAtLeast(0)}d"
}

@Composable
private fun Action(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(painterResource(icon), label) }
}

private fun Context.openSettings(action: String) {
    startActivity(Intent(action, Uri.fromParts("package", packageName, null)))
}

@Composable
private fun MoreMenu() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val partial = context.access() == Access.Partial
    if (!partial && MediaStore.canManageMedia(context)) return
    Box {
        IconButton({ open = true }) { Icon(painterResource(R.drawable.ic_more), "More") }
        DropdownMenu(open, { open = false }) {
            if (partial) {
                DropdownMenuItem({ Text("Allow access to all photos") }, {
                    open = false
                    context.openSettings(
                        SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS
                    )
                })
            }
            if (!MediaStore.canManageMedia(context)) {
                DropdownMenuItem({ Text("Skip confirmations") }, {
                    open = false
                    context.openSettings(SystemSettings.ACTION_REQUEST_MANAGE_MEDIA)
                })
            }
        }
    }
}
