package org.nacre.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Media(val uri: Uri, val video: Boolean, val raw: Boolean = false)

fun Context.ownMedia(): List<Media> {
    val columns = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE
    )
    val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
        "(${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, $video) AND " +
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    val cursor = contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        columns,
        selection,
        arrayOf(packageName),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
    ) ?: return emptyList()

    return cursor.use {
        List(it.count) { index ->
            it.moveToPosition(index)
            val isVideo = it.getInt(1) == video
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            Media(
                ContentUris.withAppendedId(collection, it.getLong(0)),
                isVideo,
                it.getString(2) == "image/x-adobe-dng"
            )
        }
    }
}

@Composable
fun rememberThumbnail(uri: Uri?, size: Int): ImageBitmap? {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(it, Size(size, size), null)
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return thumbnail
}

@Composable
fun Gallery(
    items: List<Media>,
    secure: Boolean,
    onDelete: (Media) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val pager = rememberPagerState { items.size }
    var zoomed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (items.isEmpty()) {
            Text("No photos or videos yet", Modifier.align(Alignment.Center))
        }
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { items[it].uri }
        ) { page ->
            val media = items[page]
            if (media.video) VideoPage(media.uri) else PhotoPage(media.uri) { zoomed = it }
        }

        Row(
            Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryButton(R.drawable.ic_back, "Back", onClose)
            val current = items.getOrNull(pager.currentPage)
            Text(
                if (current?.raw == true) "RAW" else "",
                Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
            if (current == null) return@Row
            if (!secure) GalleryButton(R.drawable.ic_share, "Share") { share(context, current) }
            GalleryButton(R.drawable.ic_delete, "Delete") {
                runCatching { context.contentResolver.delete(current.uri, null, null) }
                onDelete(current)
            }
        }
    }
}

@Composable
private fun GalleryButton(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(
        onClick,
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.background.copy(0.6f))
    ) {
        Icon(painterResource(icon), label)
    }
}

@Composable
private fun PhotoPage(uri: Uri, onZoomed: (Boolean) -> Unit) {
    val bitmap = rememberThumbnail(uri, 2560) ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
        onZoomed(scale > 1f)
    }

    Image(
        bitmap,
        null,
        Modifier
            .fillMaxSize()
            .transformable(state, canPan = { scale > 1f })
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
private fun VideoPage(uri: Uri) {
    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                VideoView(it).apply {
                    val controller = MediaController(it)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(uri)
                    setOnPreparedListener { seekTo(1) }
                }
            },
            onRelease = { it.stopPlayback() }
        )
    }
}

private fun share(context: Context, media: Media) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(if (media.video) "video/*" else "image/*")
        .putExtra(Intent.EXTRA_STREAM, media.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
