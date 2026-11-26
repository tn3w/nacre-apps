package org.nacre.files

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CACHE_LIMIT = 256L * 1024 * 1024

private fun descriptor(context: Context, uri: Uri, cache: File): ParcelFileDescriptor {
    val opened = context.contentResolver.openFileDescriptor(uri, "r")!!
    if (opened.statSize >= 0) return opened
    ParcelFileDescriptor.AutoCloseInputStream(opened).use { input ->
        cache.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > CACHE_LIMIT) throw IOException("PDF is too large")
                output.write(buffer, 0, read)
            }
        }
    }
    return ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewer(uri: Uri, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val document = remember { PdfDocument(context) }
    val cache = remember { File(context.cacheDir, "document.pdf") }
    var pages by remember { mutableStateOf<List<Float>?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val list = rememberLazyListState()
    val width = LocalWindowInfo.current.containerSize.width

    DisposableEffect(Unit) {
        document.bind()
        onDispose {
            document.close()
            cache.delete()
        }
    }

    BackHandler(onBack = onClose)

    LaunchedEffect(uri) {
        runCatching {
            val opened = withContext(Dispatchers.IO) {
                cache.delete()
                descriptor(context, uri, cache)
            }
            opened.use { pages = document.open(it) }
        }.onFailure {
            Toast.makeText(context, "Can't open $name", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1) },
                navigationIcon = {
                    IconButton(
                        onClose
                    ) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
                actions = {
                    pages?.let {
                        Text(
                            "${list.firstVisibleItemIndex + 1} / ${it.size}",
                            Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )
        }
    ) { padding ->
        val ratios = pages
        if (ratios == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = 0f
                        })
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } < 2 &&
                                    scale == 1f
                                ) {
                                    continue
                                }
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                val limit = (scale - 1f) * size.width / 2f
                                offset =
                                    (offset + event.calculatePan().x).coerceIn(
                                        -limit,
                                        limit
                                    )
                                if (event.changes.count { it.pressed } >= 2) {
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset
                    }
        ) {
            items(ratios.size) { index ->
                Page(document, index, ratios[index], (width * 1.5f).toInt())
            }
        }
    }
}

@Composable
private fun Page(document: PdfDocument, index: Int, ratio: Float, width: Int) {
    val bitmap by produceState<ImageBitmap?>(null, index) {
        value = runCatching { document.render(index, width).asImageBitmap() }.getOrNull()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f / ratio)
            .background(Color.White)
    ) {
        bitmap?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize()) }
    }
}
