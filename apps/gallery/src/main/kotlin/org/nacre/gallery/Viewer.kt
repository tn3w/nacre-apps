package org.nacre.gallery

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nacre.core.Palette

@Composable
fun Viewer(
    items: List<Media>,
    start: Media,
    onEdit: (Media) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val request = rememberRequest()
    val pager = rememberPagerState(items.indexOf(start).coerceAtLeast(0)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }
    KeepScreenOn(playing)
    SystemBars(chrome && !playing)

    LaunchedEffect(playing, pager.currentPage) {
        if (!playing) return@LaunchedEffect
        delay(3000)
        if (pager.currentPage < items.lastIndex) {
            pager.animateScrollToPage(pager.currentPage + 1)
        } else {
            playing = false
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { items[it].uri }
        ) { page ->
            val media = items[page]
            val toggle = {
                playing = false
                chrome = !chrome
            }
            if (media.video) {
                VideoPage(media, toggle)
            } else {
                PhotoPage(media, toggle) { zoomed = it }
            }
        }

        val current = items.getOrNull(pager.currentPage) ?: return@Box
        if (!chrome || playing) return@Box

        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Palette.Ink.copy(alpha = 0.5f)
                ).statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewerButton(R.drawable.ic_back, "Back", onClose)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    if (current.external) current.name else dateTime(current.taken),
                    color = Palette.Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                if (!current.external) {
                    Text(
                        current.album,
                        color = Palette.Silver,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            ViewerButton(R.drawable.ic_info, "Info") { dialog = "info" }
            ViewerMenu(current, items.size > 1, { dialog = it }) {
                chrome = false
                playing = true
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Palette.Ink.copy(alpha = 0.5f)
                ).navigationBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ViewerButton(R.drawable.ic_share, "Share") { share(context, listOf(current)) }
            if (!current.video) {
                ViewerButton(R.drawable.ic_edit, "Edit") {
                    onEdit(current)
                }
            }
            if (current.external) return@Row
            ViewerButton(
                favoriteIcon(current.favorite),
                "Favorite"
            ) {
                request(
                    context.favoriteRequest(listOf(current.uri), !current.favorite)
                ) {}
            }
            ViewerButton(R.drawable.ic_delete, "Move to trash") {
                request(context.trashRequest(listOf(current.uri), true)) {}
            }
        }

        when (dialog) {
            "info" -> InfoSheet(current) { dialog = null }
            "rename" -> RenameDialog(current) { dialog = null }
            "move" -> MoveDialog(listOf(current)) { dialog = null }
            "wallpaper" -> WallpaperDialog(current.uri) { dialog = null }
        }
    }
}

private fun favoriteIcon(favorite: Boolean) =
    if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun SystemBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val dark = isSystemInDarkTheme()
    DisposableEffect(visible, dark) {
        val controller = window.insetsController ?: return@DisposableEffect onDispose {}
        val light =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(0, light)
        controller.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) {
            controller.show(WindowInsets.Type.systemBars())
        } else {
            controller.hide(WindowInsets.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsets.Type.systemBars())
            controller.setSystemBarsAppearance(if (dark) 0 else light, light)
        }
    }
}

@Composable
private fun ViewerMenu(
    media: Media,
    slideshow: Boolean,
    onDialog: (String) -> Unit,
    onSlideshow: () -> Unit
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        ViewerButton(R.drawable.ic_more, "More") { open = true }
        DropdownMenu(open, { open = false }) {
            fun item(label: String, action: () -> Unit) = @Composable {
                DropdownMenuItem({ Text(label) }, {
                    open = false
                    action()
                })
            }
            if (slideshow) item("Slideshow", onSlideshow)()
            if (!media.video) item("Set as wallpaper") { onDialog("wallpaper") }()
            if (!media.video) item("Use as") { attach(context, media) }()
            item("Open with") { openWith(context, media) }()
            if (!media.external) item("Move to album") { onDialog("move") }()
            if (!media.external) item("Rename") { onDialog("rename") }()
        }
    }
}

@Composable
private fun ViewerButton(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(painterResource(icon), label, tint = Palette.Paper) }
}

@Composable
private fun PhotoPage(media: Media, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preview = rememberThumbnail(media.uri, 512)
    val full by produceState<ImageBitmap?>(null, media.uri) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    decode(context, media.uri, 4096).asImageBitmap()
                }.getOrNull()
            }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state =
        rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset = if (scale == 1f) Offset.Zero else offset + pan * scale
            onZoomed(scale > 1f)
        }
    val bitmap = full ?: preview ?: return

    Image(
        bitmap,
        null,
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { point ->
                        val zoomIn = scale == 1f
                        scale = if (zoomIn) 2.5f else 1f
                        offset =
                            if (zoomIn) {
                                (Offset(size.width / 2f, size.height / 2f) - point) *
                                    2.5f
                            } else {
                                Offset.Zero
                            }
                        onZoomed(zoomIn)
                    }
                )
            }.transformable(state, canPan = { scale > 1f })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun VideoPage(media: Media, onTap: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } },
        contentAlignment = Alignment.Center
    ) {
        if (playing) {
            AndroidView(
                factory = {
                    VideoView(it).apply {
                        val controller = MediaController(it)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(media.uri)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() }
            )
            return@Box
        }
        rememberThumbnail(media.uri, 1024)?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        IconButton(
            { playing = true },
            Modifier
                .size(
                    72.dp
                ).clip(CircleShape)
                .background(Palette.Ink.copy(alpha = 0.6f))
        ) { Icon(painterResource(R.drawable.ic_play), "Play", tint = Palette.Paper) }
    }
}

fun dateTime(millis: Long): String = Instant
    .ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(media: Media, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState(emptyList<Pair<String, String>>(), media.uri) {
        value = withContext(Dispatchers.IO) { details(context, media) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(details) { (label, value) ->
                val geo = label == "Location"
                ListItem(
                    headlineContent = { Text(value) },
                    overlineContent = { Text(label) },
                    modifier = Modifier.clickable(geo) { openMap(context, value) }
                )
            }
        }
    }
}

private fun details(context: Context, media: Media): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows += "Name" to media.name
    if (!media.external) {
        rows += "Date" to dateTime(media.taken)
        rows += "Path" to "${media.path}${media.name}"
        rows += "Size" to formatSize(media.size)
    }
    if (media.width > 0) rows += "Resolution" to "${media.width} × ${media.height}"
    if (media.video) rows += "Duration" to formatDuration(media.duration)
    if (media.video) return rows
    val exif = exif(context, media.uri) ?: return rows
    val camera =
        listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_MAKE),
            exif.getAttribute(ExifInterface.TAG_MODEL)
        ).joinToString(" ")
    if (camera.isNotBlank()) rows += "Camera" to camera
    val settings =
        listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "ƒ/$it" },
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                .takeIf { it > 0 }
                ?.let { if (it < 1) "1/${(1 / it).roundToInt()} s" else "$it s" },
            exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0 }
                ?.let { "$it mm" }
        ).joinToString("  ·  ")
    if (settings.isNotBlank()) rows += "Settings" to settings
    val location = FloatArray(2)
    if (exif.getLatLong(location)) {
        rows += "Location" to "%.5f, %.5f".format(Locale.ROOT, location[0], location[1])
    }
    return rows
}

private fun exif(context: Context, uri: Uri): ExifInterface? {
    val resolver = context.contentResolver
    val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    return listOf(original, uri).firstNotNullOfOrNull { candidate ->
        runCatching {
            resolver.openInputStream(candidate)!!.use { ExifInterface(it) }
        }.getOrNull()
    }
}

@Composable
fun RenameDialog(media: Media, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = rememberRequest()
    val extension = media.name.substringAfterLast('.', "")
    var name by remember { mutableStateOf(media.name.substringBeforeLast('.')) }
    val clean = safeName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(
                {
                    val full = if (extension.isEmpty()) clean else "$clean.$extension"
                    request(context.writeRequest(listOf(media.uri))) {
                        scope.launch { Library.rename(context, media.uri, full) }
                    }
                    onDismiss()
                },
                enabled = clean.isNotEmpty()
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun MoveDialog(items: List<Media>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = rememberRequest()
    val all by Library.media.collectAsState()
    val albums =
        remember(all) { all.albums(AlbumSort.Name).filter { it.path.isNotEmpty() } }
    var name by remember { mutableStateOf("") }
    val clean = safeName(name)

    fun move(path: String) {
        request(context.writeRequest(items.map { it.uri })) {
            scope.launch { Library.move(context, items.map { it.uri }, path) }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to album") },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text("New album") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                { move("${Environment.DIRECTORY_PICTURES}/$clean/") },
                                enabled = clean.isNotEmpty()
                            ) {
                                Icon(painterResource(R.drawable.ic_check), "Create")
                            }
                        }
                    )
                    Spacer(Modifier.size(8.dp))
                }
                items(albums, key = { it.id }) { album ->
                    ListItem(
                        headlineContent = { Text(album.name) },
                        supportingContent = { Text(album.path) },
                        leadingContent = {
                            Icon(painterResource(R.drawable.ic_folder), null)
                        },
                        modifier = Modifier.clickable { move(album.path) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun WallpaperDialog(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun set(which: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use {
                    WallpaperManager.getInstance(context).setStream(it, null, true, which)
                }
            }
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set as wallpaper") },
        text = {
            Column {
                ListItem(
                    { Text("Home screen") },
                    Modifier.clickable { set(WallpaperManager.FLAG_SYSTEM) }
                )
                ListItem(
                    { Text("Lock screen") },
                    Modifier.clickable { set(WallpaperManager.FLAG_LOCK) }
                )
                ListItem(
                    { Text("Both") },
                    Modifier.clickable {
                        set(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

fun share(context: Context, items: List<Media>) {
    if (items.isEmpty()) return
    val type =
        when {
            items.all { it.video } -> "video/*"
            items.none { it.video } -> "image/*"
            else -> "*/*"
        }
    val intent =
        if (items.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(items.map { it.uri })
                )
        }
    intent.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, Intent.createChooser(intent, null))
}

private fun attach(context: Context, media: Media) {
    val intent =
        Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(media.uri, media.mime)
            .putExtra("mimeType", media.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, Intent.createChooser(intent, "Use as"))
}

private fun openWith(context: Context, media: Media) {
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(media.uri, media.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, Intent.createChooser(intent, "Open with"))
}

private fun openMap(context: Context, coordinates: String) {
    val point = coordinates.replace(" ", "")
    start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point")))
}

private fun start(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}
