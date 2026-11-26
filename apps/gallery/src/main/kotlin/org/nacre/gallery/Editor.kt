package org.nacre.gallery

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nacre.core.Palette

enum class Tool(val icon: Int) {
    Crop(R.drawable.ic_crop),
    Adjust(R.drawable.ic_tune),
    Filters(R.drawable.ic_filter),
    Markup(R.drawable.ic_draw)
}

private val INKS =
    listOf(
        Palette.Paper,
        Palette.Ink,
        Color(0xFFE53935),
        Color(0xFFFDD835),
        Color(0xFF43A047),
        Color(0xFF1E88E5),
        Color(0xFF8E24AA)
    )

private const val MIN_CROP = 0.08f

@Composable
fun Editor(media: Media, onSaved: (Uri) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(Edit()) }
    val history = remember { mutableStateListOf<Edit>() }
    var tool by remember { mutableStateOf(Tool.Crop) }
    var saving by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    LaunchedEffect(media.uri) {
        source =
            withContext(Dispatchers.IO) {
                runCatching { decode(context, media.uri, 1600) }.getOrNull()
            }
        failed = source == null
    }

    fun begin() {
        history += edit
    }

    fun commit(change: Edit) {
        begin()
        edit = change
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_close), "Cancel") }
            Spacer(Modifier.weight(1f))
            IconButton({
                edit = history.removeAt(history.lastIndex)
            }, enabled = history.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_undo), "Undo")
            }
            TextButton({ commit(Edit()) }, enabled = edit != Edit()) { Text("Reset") }
            TextButton(
                {
                    saving = true
                    scope.launch {
                        val uri =
                            withContext(Dispatchers.Default) {
                                runCatching { save(context, media, edit) }.getOrNull()
                            }
                        saving = false
                        if (uri != null) onSaved(uri) else failed = true
                    }
                },
                enabled = !saving && source != null && edit != Edit()
            ) { Text("Save copy") }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = source
            when {
                failed -> {
                    Text("Can't edit this image", textAlign = TextAlign.Center)
                }

                bitmap == null || saving -> {
                    CircularProgressIndicator()
                }

                tool == Tool.Crop -> {
                    CropCanvas(bitmap, edit, ::begin) { edit = it }
                }

                else -> {
                    Preview(bitmap, edit, tool == Tool.Markup) {
                        commit(edit.copy(strokes = edit.strokes + it))
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            when (tool) {
                Tool.Crop -> CropTools(edit, source, ::commit, ::begin) { edit = it }
                Tool.Adjust -> AdjustTools(edit, ::begin) { edit = it }
                Tool.Filters -> FilterTools(edit, ::commit)
                Tool.Markup -> MarkupTools()
            }
        }

        NavigationBar {
            Tool.entries.forEach {
                NavigationBarItem(
                    selected = tool == it,
                    onClick = { tool = it },
                    icon = { Icon(painterResource(it.icon), null) },
                    label = { Text(it.name) }
                )
            }
        }
    }
}

@Composable
private fun rendered(source: Bitmap, edit: Edit, cropped: Boolean): ImageBitmap? {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, edit, cropped) {
        image =
            withContext(Dispatchers.Default) {
                render(source, edit, cropped).asImageBitmap()
            }
    }
    return image
}

private var marker by mutableStateOf(false)
private var ink by mutableStateOf(INKS[2])
private var penWidth by mutableStateOf(0.012f)

@Composable
private fun Preview(
    source: Bitmap,
    edit: Edit,
    drawing: Boolean,
    onStroke: (Stroke) -> Unit
) {
    val image = rendered(source, edit, cropped = true) ?: return
    val points = remember { mutableStateListOf<Offset>() }
    Box(Modifier.aspectRatio(image.width.toFloat() / image.height)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (!drawing) return@Box
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                fun normalized(offset: Offset) = Offset(
                    (offset.x / size.width).coerceIn(0f, 1f),
                    (offset.y / size.height).coerceIn(0f, 1f)
                )
                detectDragGestures(
                    onDragStart = { points += normalized(it) },
                    onDrag = { change, _ -> points += normalized(change.position) },
                    onDragEnd = {
                        onStroke(currentStroke(points.toList()))
                        points.clear()
                    },
                    onDragCancel = { points.clear() }
                )
            }
        ) {
            if (points.isEmpty()) return@Canvas
            draw(
                drawContext.canvas.nativeCanvas,
                currentStroke(points),
                size.width,
                size.height
            )
        }
    }
}

private fun currentStroke(points: List<Offset>) = Stroke(
    points,
    ink.toArgb(),
    if (marker) penWidth * 3 else penWidth,
    marker
)

@Composable
private fun CropCanvas(
    source: Bitmap,
    edit: Edit,
    onBegin: () -> Unit,
    onChange: (Edit) -> Unit
) {
    val image = rendered(source, edit, cropped = false) ?: return
    val ratio = image.width.toFloat() / image.height
    var edges by remember { mutableStateOf(Edges()) }
    val current by rememberUpdatedState(edit)

    Box(Modifier.aspectRatio(ratio)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        edges =
                            grab(
                                current.crop,
                                start.x / size.width,
                                start.y / size.height,
                                48.dp.toPx() / size.width
                            )
                        onBegin()
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val moved =
                            edges.drag(
                                current.crop,
                                drag.x / size.width,
                                drag.y / size.height
                            )
                        val target = current.aspect.target(ratio)
                        onChange(current.copy(crop = moved.fit(target, edges, ratio)))
                    }
                )
            }
        ) { drawCrop(edit.crop) }
    }
}

private data class Edges(
    val left: Boolean = false,
    val top: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false
) {
    val none get() = !left && !top && !right && !bottom

    fun drag(crop: RectF, dx: Float, dy: Float): RectF {
        if (none) {
            val x = dx.coerceIn(-crop.left, 1f - crop.right)
            val y = dy.coerceIn(-crop.top, 1f - crop.bottom)
            return RectF(crop.left + x, crop.top + y, crop.right + x, crop.bottom + y)
        }
        return RectF(
            if (left) (crop.left + dx).coerceIn(0f, crop.right - MIN_CROP) else crop.left,
            if (top) (crop.top + dy).coerceIn(0f, crop.bottom - MIN_CROP) else crop.top,
            if (right) {
                (crop.right + dx).coerceIn(
                    crop.left + MIN_CROP,
                    1f
                )
            } else {
                crop.right
            },
            if (bottom) {
                (crop.bottom + dy).coerceIn(
                    crop.top + MIN_CROP,
                    1f
                )
            } else {
                crop.bottom
            }
        )
    }
}

private fun grab(crop: RectF, x: Float, y: Float, reach: Float) = Edges(
    left = abs(x - crop.left) < reach,
    top = abs(y - crop.top) < reach,
    right = abs(x - crop.right) < reach,
    bottom = abs(y - crop.bottom) < reach
)

private fun Aspect.target(imageRatio: Float) = if (ratio == 0f) imageRatio else ratio

private fun RectF.fit(target: Float?, edges: Edges, imageRatio: Float): RectF {
    if (target == null || edges.none) return this
    val scaled = target / imageRatio
    val width = width().coerceAtMost(1f)
    var height = width / scaled
    var newWidth = width
    if (height > 1f) {
        height = 1f
        newWidth = height * scaled
    }
    val left = if (edges.left) right - newWidth else left
    val top = if (edges.top) bottom - height else top
    return RectF(left, top, left + newWidth, top + height).clamped()
}

private fun RectF.clamped(): RectF {
    val x =
        when {
            left < 0f -> -left
            right > 1f -> 1f - right
            else -> 0f
        }
    val y =
        when {
            top < 0f -> -top
            bottom > 1f -> 1f - bottom
            else -> 0f
        }
    return RectF(left + x, top + y, right + x, bottom + y)
}

fun centered(target: Float?, imageRatio: Float): RectF {
    if (target == null) return RectF(0f, 0f, 1f, 1f)
    val scaled = target / imageRatio
    return if (scaled >= 1f) {
        val height = 1f / scaled
        RectF(0f, (1f - height) / 2, 1f, (1f + height) / 2)
    } else {
        RectF((1f - scaled) / 2, 0f, (1f + scaled) / 2, 1f)
    }
}

private fun DrawScope.drawCrop(crop: RectF) {
    val left = crop.left * size.width
    val top = crop.top * size.height
    val right = crop.right * size.width
    val bottom = crop.bottom * size.height
    val shade = Palette.Ink.copy(alpha = 0.6f)
    drawRect(shade, size = size.copy(height = top))
    drawRect(shade, Offset(0f, bottom), size.copy(height = size.height - bottom))
    drawRect(
        shade,
        Offset(0f, top),
        Size(left, bottom - top)
    )
    drawRect(
        shade,
        Offset(right, top),
        Size(size.width - right, bottom - top)
    )
    val line = Palette.Paper
    for (third in 1..2) {
        val x = left + (right - left) * third / 3
        val y = top + (bottom - top) * third / 3
        drawLine(line.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom))
        drawLine(line.copy(alpha = 0.5f), Offset(left, y), Offset(right, y))
    }
    drawRect(
        line,
        Offset(left, top),
        Size(right - left, bottom - top),
        style = StrokeStyle(2.dp.toPx())
    )
    val handle = 20.dp.toPx()
    val thick = 4.dp.toPx()
    listOf(left to top, right to top, left to bottom, right to bottom).forEach { (x, y) ->
        val dx = if (x == left) handle else -handle
        val dy = if (y == top) handle else -handle
        drawLine(line, Offset(x, y), Offset(x + dx, y), thick)
        drawLine(line, Offset(x, y), Offset(x, y + dy), thick)
    }
}

@Composable
private fun CropTools(
    edit: Edit,
    source: Bitmap?,
    onCommit: (Edit) -> Unit,
    onBegin: () -> Unit,
    onChange: (Edit) -> Unit
) {
    fun ratio(change: Edit): Float {
        val (width, height) = change.orientedSize(source?.width ?: 1, source?.height ?: 1)
        return width.toFloat() / height
    }

    fun reframe(change: Edit): Edit {
        val imageRatio = ratio(change)
        return change.copy(crop = centered(change.aspect.target(imageRatio), imageRatio))
    }

    var dragging by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton({ onCommit(reframe(edit.copy(turns = (edit.turns + 3) % 4))) }) {
                Icon(painterResource(R.drawable.ic_rotate), "Rotate")
            }
            IconButton({ onCommit(edit.copy(flip = !edit.flip)) }) {
                Icon(painterResource(R.drawable.ic_flip), "Flip")
            }
            Slider(
                edit.angle,
                {
                    if (!dragging) onBegin()
                    dragging = true
                    onChange(edit.copy(angle = it))
                },
                Modifier.weight(1f),
                valueRange = -45f..45f,
                onValueChangeFinished = { dragging = false }
            )
            Text(
                "${edit.angle.roundToInt()}°",
                Modifier.width(44.dp),
                textAlign = TextAlign.End
            )
        }
        Chips(Aspect.entries, edit.aspect, { it.label }) {
            onCommit(reframe(edit.copy(aspect = it)))
        }
    }
}

@Composable
private fun AdjustTools(edit: Edit, onBegin: () -> Unit, onChange: (Edit) -> Unit) {
    var selected by remember { mutableStateOf(Adjust.Exposure) }
    var dragging by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                edit[selected],
                {
                    if (!dragging) onBegin()
                    dragging = true
                    val value = if (abs(it) < 0.03f) 0f else it
                    onChange(edit.copy(adjust = edit.adjust + (selected to value)))
                },
                Modifier.weight(1f),
                valueRange = -1f..1f,
                onValueChangeFinished = { dragging = false }
            )
            Text(
                "${(edit[selected] * 100).roundToInt()}",
                Modifier.width(44.dp),
                textAlign = TextAlign.End
            )
        }
        Chips(Adjust.entries, selected, {
            if (edit[it] !=
                0f
            ) {
                "${it.name} •"
            } else {
                it.name
            }
        }) {
            selected = it
        }
    }
}

@Composable
private fun FilterTools(edit: Edit, onCommit: (Edit) -> Unit) {
    Chips(Filter.entries, edit.filter, { it.name }) { onCommit(edit.copy(filter = it)) }
}

@Composable
private fun MarkupTools() {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            INKS.forEach { color ->
                Box(
                    Modifier
                        .size(if (ink == color) 34.dp else 28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { ink = color }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(!marker, { marker = false }, { Text("Pen") })
            Spacer(Modifier.width(8.dp))
            FilterChip(marker, { marker = true }, { Text("Highlighter") })
            Slider(
                penWidth,
                { penWidth = it },
                Modifier.weight(1f).padding(start = 12.dp),
                valueRange = 0.004f..0.04f
            )
        }
    }
}

@Composable
private fun <T> Chips(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach {
            FilterChip(
                selected == it,
                { onSelect(it) },
                { Text(label(it)) },
                shape = RoundedCornerShape(50)
            )
        }
    }
}
