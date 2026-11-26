package org.nacre.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nacre.core.Palette
import kotlin.math.abs
import kotlin.math.roundToInt

private val STRIP = 48.dp

private class Colors(
    dark: Boolean,
) {
    val board = if (dark) Palette.Ink else Palette.Mist
    val key = if (dark) Palette.Graphite else Palette.Paper
    val function = if (dark) Palette.Ash.copy(alpha = 0.32f) else Palette.Silver
    val pressed =
        if (dark) {
            Palette.Ash.copy(
                alpha = 0.6f,
            )
        } else {
            Palette.Ash.copy(alpha = 0.5f)
        }
    val text = if (dark) Palette.Paper else Palette.Ink
    val hint = Palette.Ash
    val accent = if (dark) Palette.Paper else Palette.Ink
    val onAccent = if (dark) Palette.Ink else Palette.Paper
}

class Placed(
    val key: Key,
    val bounds: Rect,
)

private data class Popup(
    val anchor: Placed,
    val options: List<String>,
    val bounds: Rect,
    val selected: Int,
    val pointer: PointerId,
)

@Composable
fun Keyboard(service: KeyboardService) {
    val colors = Colors(isSystemInDarkTheme())
    val toggles by Store.toggles.collectAsState()
    val languages by Store.languages.collectAsState()
    val height by Store.height.collectAsState()
    val numberRow = toggles[Toggle.NumberRow] == true && service.page == Page.Letters
    val rows =
        layout(service.page, languages.first(), numberRow, service.comma, languages.size)
    val rowHeight =
        when (service.page) {
            Page.Numbers, Page.Phone -> height.row.dp * 1.25f
            else -> height.row.dp
        }
    val keysHeight = height.row.dp * if (numberRow) 5 else 4
    Column(Modifier.fillMaxWidth().background(colors.board).navigationBarsPadding()) {
        Strip(service, colors, toggles[Toggle.Suggestions] != false)
        when (service.panel) {
            Panel.Keys -> {
                Keys(
                    service,
                    rows,
                    rowHeight,
                    colors,
                    toggles[Toggle.Popup] != false,
                )
            }

            Panel.Emoji -> {
                EmojiPanel(service, keysHeight, colors)
            }

            Panel.Clipboard -> {
                ClipPanel(service, keysHeight, colors)
            }
        }
    }
}

@Composable
private fun Strip(
    service: KeyboardService,
    colors: Colors,
    show: Boolean,
) {
    val suggestions = service.suggestions
    val words =
        when {
            service.panel != Panel.Keys || !show -> {
                emptyList()
            }

            suggestions.typed.isNotEmpty() -> {
                val center = suggestions.correction ?: suggestions.words.firstOrNull()
                val other = suggestions.words.firstOrNull { it != center }
                listOf(suggestions.typed, center, other)
            }

            else -> {
                service.predictions
            }
        }
    Row(
        Modifier.fillMaxWidth().height(STRIP).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (words.isEmpty()) return@Row Toolbar(service, colors)
        words.forEachIndexed { index, word ->
            if (index > 0) Box(Modifier.width(1.dp).height(20.dp).background(colors.hint))
            val typed = suggestions.typed.isNotEmpty() && index == 0
            val quoted = typed && suggestions.correction != null
            Suggestion(
                text = if (quoted) "“$word”" else word.orEmpty(),
                bold = index == 1 && suggestions.correction != null,
                color = colors.text,
            ) { word?.let(service::pick) }
        }
    }
}

@Composable
private fun RowScope.Suggestion(
    text: String,
    bold: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable(
            enabled = text.isNotEmpty(),
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = color,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun RowScope.Toolbar(
    service: KeyboardService,
    colors: Colors,
) {
    val context = LocalContext.current
    val clips by Store.clips.collectAsState()

    fun toggle(panel: Panel) {
        service.panel = if (service.panel == panel) Panel.Keys else panel
    }
    ToolButton(R.drawable.ic_emoji, service.panel == Panel.Emoji, colors) {
        toggle(Panel.Emoji)
    }
    ToolButton(R.drawable.ic_clipboard, service.panel == Panel.Clipboard, colors) {
        toggle(Panel.Clipboard)
    }
    ToolButton(R.drawable.ic_settings, false, colors) { openSettings(context) }
    Box(
        Modifier.weight(1f).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fresh =
            clips
                .firstOrNull()
                ?.takeIf {
                    System.currentTimeMillis() - it.time < 60_000 &&
                        service.panel == Panel.Keys
                }
        if (fresh != null) {
            Text(
                fresh.text.replace('\n', ' '),
                color = colors.text,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.key)
                        .clickable { service.insert(fresh.text) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    ToolButton(R.drawable.ic_hide, false, colors) { service.requestHideSelf(0) }
}

@Composable
private fun ToolButton(
    icon: Int,
    active: Boolean,
    colors: Colors,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp, 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) colors.function else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colors.text)
    }
}

private fun openSettings(context: Context) {
    val intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    context.startActivity(intent)
}

private fun place(
    rows: List<List<Key>>,
    width: Float,
    rowHeight: Float,
): List<Placed> {
    val units =
        rows
            .filter { row -> row.none(Key::flexible) }
            .maxOfOrNull { row -> row.sumOf { it.width.toDouble() } }
            ?.toFloat() ?: 10f
    val unit = width / units
    return rows.flatMapIndexed { index, row ->
        val fixed = row.sumOf { it.width.toDouble() }.toFloat()
        val flexible = row.count(Key::flexible)
        val flexWidth = if (flexible > 0) (units - fixed) / flexible else 0f
        var x = (units - fixed - flexWidth * flexible) / 2 * unit
        row.map { key ->
            val keyWidth = (if (key.flexible) flexWidth else key.width) * unit
            val top = index * rowHeight
            Placed(
                key,
                Rect(x, top, x + keyWidth, top + rowHeight),
            ).also { x += keyWidth }
        }
    }
}

private fun List<Placed>.hit(
    position: Offset,
    rowHeight: Float,
): Placed {
    val row =
        (position.y / rowHeight).toInt().coerceIn(
            0,
            maxOf { it.bounds.bottom / rowHeight }
                .roundToInt() - 1,
        )
    return filter { (it.bounds.top / rowHeight).roundToInt() == row }.minBy {
        when {
            position.x < it.bounds.left -> it.bounds.left - position.x
            position.x > it.bounds.right -> position.x - it.bounds.right
            else -> 0f
        }
    }
}

private class Pointer(
    var placed: Placed,
    val start: Offset,
) {
    var job: Job? = null
    var handled = false
    var moved = 0
}

private class Touches(
    val service: KeyboardService,
    val keys: List<Placed>,
    val rowHeight: Float,
    val width: Float,
    val step: Float,
    val view: View,
    val scope: CoroutineScope,
) {
    val pressed = mutableStateListOf<Placed>()
    var popup by mutableStateOf<Popup?>(null)
    private val pointers = HashMap<PointerId, Pointer>()

    suspend fun handle(input: PointerInputScope) =
        input.awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { change ->
                    when {
                        change.changedToDownIgnoreConsumed() -> {
                            down(
                                change.id,
                                change.position,
                            )
                        }

                        change.changedToUpIgnoreConsumed() -> {
                            up(change.id)
                        }

                        change.pressed -> {
                            move(change.id, change.position)
                        }
                    }
                    change.consume()
                }
            }
        }

    private fun down(
        id: PointerId,
        position: Offset,
    ) {
        val placed = keys.hit(position, rowHeight)
        val pointer = Pointer(placed, position)
        pointers[id] = pointer
        pressed += placed
        service.feedback(view, placed.key)
        when (placed.key.action) {
            Action.Shift -> {
                service.press(placed.key)
                pointer.handled = true
            }

            Action.Delete -> {
                service.delete(0)
                pointer.handled = true
                pointer.job =
                    scope.launch {
                        delay(400)
                        var repeats = 1
                        while (true) {
                            service.delete(repeats++)
                            service.feedback(view, placed.key)
                            delay(55)
                        }
                    }
            }

            else -> {
                pointer.job =
                    scope.launch {
                        delay(380)
                        hold(id, pointer)
                    }
            }
        }
    }

    private fun hold(
        id: PointerId,
        pointer: Pointer,
    ) {
        val placed = pointer.placed
        val key = placed.key
        val options =
            key.popup.map {
                if (service.shift ==
                    Shift.Off
                ) {
                    it
                } else {
                    it.uppercase()
                }
            }
        when {
            key.action == Action.Space || key.action == Action.Language -> {
                service
                    .showPicker()
            }

            key.hold != null -> {
                service.press(Key("", key.hold))
            }

            options.isNotEmpty() -> {
                popup = popupFor(placed, options, id)
            }

            else -> {
                return
            }
        }
        pointer.handled = true
        service.feedback(view, null)
    }

    private fun popupFor(
        placed: Placed,
        options: List<String>,
        id: PointerId,
    ): Popup {
        val cell = placed.bounds.width.coerceAtMost(width / options.size)
        val left =
            (placed.bounds.left - cell * 0.25f).coerceIn(
                0f,
                width - cell * options.size,
            )
        val top = placed.bounds.top - rowHeight
        return Popup(
            placed,
            options,
            Rect(
                left,
                top,
                left + cell * options.size,
                top + rowHeight,
            ),
            0,
            id,
        )
    }

    private fun move(
        id: PointerId,
        position: Offset,
    ) {
        val pointer = pointers[id] ?: return
        val current = popup
        if (current != null && current.pointer == id) {
            val cell = current.bounds.width / current.options.size
            val index = ((position.x - current.bounds.left) / cell).toInt()
            popup = current.copy(selected = index.coerceIn(0, current.options.lastIndex))
            return
        }
        if (pointer.handled) return
        if (pointer.placed.key.action ==
            Action.Space
        ) {
            return swipeSpace(pointer, position)
        }
        val placed = keys.hit(position, rowHeight)
        if (placed == pointer.placed) return
        pressed -= pointer.placed
        pressed += placed
        pointer.placed = placed
        pointer.job?.cancel()
        pointer.job =
            scope.launch {
                delay(380)
                hold(id, pointer)
            }
    }

    private fun swipeSpace(
        pointer: Pointer,
        position: Offset,
    ) {
        val steps = ((position.x - pointer.start.x) / step).toInt()
        if (steps == pointer.moved) return
        pointer.job?.cancel()
        service.moveCursor(steps - pointer.moved)
        pointer.moved = steps
    }

    private fun up(id: PointerId) {
        val pointer = pointers.remove(id) ?: return
        pointer.job?.cancel()
        pressed -= pointer.placed
        val current = popup
        if (current != null && current.pointer == id) {
            popup = null
            service.type(current.options[current.selected])
            return
        }
        if (pointer.handled || pointer.moved != 0) return
        service.press(pointer.placed.key)
    }
}

@Composable
private fun Keys(
    service: KeyboardService,
    rows: List<List<Key>>,
    rowHeight: Dp,
    colors: Colors,
    previews: Boolean,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowHeight * rows.size)) {
        val width = constraints.maxWidth.toFloat()
        val rowPixels = with(density) { rowHeight.toPx() }
        val step = with(density) { 14.dp.toPx() }
        val keys = remember(rows, width, rowPixels) { place(rows, width, rowPixels) }
        val touches =
            remember(keys) {
                Touches(service, keys, rowPixels, width, step, view, scope)
            }
        Box(Modifier.fillMaxSize().pointerInput(touches) { touches.handle(this) }) {
            keys.forEach { KeyView(service, it, it in touches.pressed, colors) }
            val preview = touches.pressed.lastOrNull()
            if (previews && touches.popup == null &&
                preview?.key?.action == Action.Text
            ) {
                Preview(service, preview, colors)
            }
            touches.popup?.let { PopupView(it, colors) }
        }
    }
}

@Composable
private fun Modifier.at(bounds: Rect): Modifier {
    val density = LocalDensity.current
    return offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
        .size(
            with(density) { bounds.width.toDp() },
            with(density) { bounds.height.toDp() },
        )
}

@Composable
private fun KeyView(
    service: KeyboardService,
    placed: Placed,
    pressed: Boolean,
    colors: Colors,
) {
    val key = placed.key
    val accent = key.action == Action.Enter
    val plain = key.action == Action.Text || key.action == Action.Space
    val background =
        when {
            pressed -> colors.pressed
            accent -> colors.accent
            plain -> colors.key
            else -> colors.function
        }
    val tint = if (accent && !pressed) colors.onAccent else colors.text
    Box(
        Modifier
            .at(placed.bounds)
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        val icon = icon(service, key)
        when {
            icon != null -> {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tint)
            }

            key.action == Action.Space -> {
                Text(
                    if (service.page == Page.Letters) service.language.title else "",
                    color = colors.hint,
                    fontSize = 13.sp,
                )
            }

            else -> {
                Text(
                    label(service, key),
                    color = tint,
                    fontSize = if (key.action == Action.Text) 22.sp else 15.sp,
                )
            }
        }
        if (key.hint.isNotEmpty()) {
            Text(
                key.hint,
                color = colors.hint,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp),
            )
        }
    }
}

private fun label(
    service: KeyboardService,
    key: Key,
) = if (service.shift == Shift.Off) key.text else key.text.uppercase()

private fun icon(
    service: KeyboardService,
    key: Key,
) = when (key.action) {
    Action.Shift -> {
        when (service.shift) {
            Shift.Off -> R.drawable.ic_shift
            Shift.Once -> R.drawable.ic_shift_on
            Shift.Locked -> R.drawable.ic_shift_lock
        }
    }

    Action.Delete -> {
        R.drawable.ic_backspace
    }

    Action.Language -> {
        R.drawable.ic_language
    }

    Action.Emoji -> {
        R.drawable.ic_emoji
    }

    Action.Enter -> {
        when (service.action) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_next
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
            else -> R.drawable.ic_enter
        }
    }

    else -> {
        null
    }
}

@Composable
private fun Preview(
    service: KeyboardService,
    placed: Placed,
    colors: Colors,
) {
    val bounds = placed.bounds
    val lift = with(LocalDensity.current) { STRIP.toPx() }
    val top = (bounds.top - bounds.height * 1.1f).coerceAtLeast(-lift)
    val area = Rect(bounds.left, top, bounds.right, top + bounds.height)
    Box(
        Modifier
            .at(area)
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.key),
        contentAlignment = Alignment.Center,
    ) {
        Text(label(service, placed.key), color = colors.text, fontSize = 30.sp)
    }
}

@Composable
private fun PopupView(
    popup: Popup,
    colors: Colors,
) {
    Row(
        Modifier.at(popup.bounds).clip(RoundedCornerShape(10.dp)).background(colors.key),
    ) {
        popup.options.forEachIndexed { index, option ->
            val selected = index == popup.selected
            Box(
                Modifier
                    .weight(
                        1f,
                    ).fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    color = if (selected) colors.onAccent else colors.text,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

private var emojiCache: List<Pair<String, List<String>>>? = null

private fun loadEmoji(context: Context) =
    emojiCache ?: run {
        val paint = Paint()
        context.assets
            .open("emoji.txt")
            .bufferedReader()
            .readLines()
            .map { line ->
                val (name, items) = line.split('\t')
                name to items.split(' ').filter(paint::hasGlyph)
            }.also { emojiCache = it }
    }

@Composable
private fun EmojiPanel(
    service: KeyboardService,
    height: Dp,
    colors: Colors,
) {
    val context = LocalContext.current
    val groups by produceState(emojiCache.orEmpty()) {
        value = withContext(Dispatchers.Default) { loadEmoji(context) }
    }
    val recents by Store.recents.collectAsState()
    val categories = listOf("Recent" to recents) + groups
    var selected by remember { mutableIntStateOf(if (recents.isEmpty()) 1 else 0) }
    Column(Modifier.fillMaxWidth().height(height)) {
        LazyVerticalGrid(
            GridCells.Adaptive(46.dp),
            Modifier.weight(1f).padding(horizontal = 4.dp),
        ) {
            items(categories.getOrNull(selected)?.second.orEmpty()) { emoji ->
                Box(
                    Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { service.emoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 26.sp) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelKey("ABC", colors) { service.panel = Panel.Keys }
            categories.forEachIndexed { index, (_, items) ->
                val symbol = if (index == 0) "🕘" else items.firstOrNull().orEmpty()
                Box(
                    Modifier
                        .weight(
                            1f,
                        ).fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (index ==
                                selected
                            ) {
                                colors.function
                            } else {
                                Color.Transparent
                            },
                        ).clickable { selected = index },
                    contentAlignment = Alignment.Center,
                ) { Text(symbol, fontSize = 18.sp) }
            }
            Box(
                Modifier
                    .size(52.dp, 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.function)
                    .clickable { service.delete(0) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_backspace), null, tint = colors.text)
            }
        }
    }
}

@Composable
private fun PanelKey(
    text: String,
    colors: Colors,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(
                52.dp,
                40.dp,
            ).clip(RoundedCornerShape(8.dp))
            .background(colors.function)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = colors.text, fontSize = 15.sp) }
}

@Composable
private fun ClipPanel(
    service: KeyboardService,
    height: Dp,
    colors: Colors,
) {
    val clips by Store.clips.collectAsState()
    val toggles by Store.toggles.collectAsState()
    val now = System.currentTimeMillis()
    val visible = clips.filter { now - it.time < Store.CLIP_LIFETIME }
    Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PanelKey("ABC", colors) { service.panel = Panel.Keys }
            Text(
                "Clipboard",
                color = colors.text,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (visible.isNotEmpty()) {
                PanelKey("Clear", colors) { visible.forEach(Store::removeClip) }
            }
        }
        if (visible.isEmpty()) {
            val message =
                if (toggles[Toggle.Clipboard] == false) {
                    "Clipboard history is off"
                } else {
                    "Text you copy stays here for an hour and is never saved to storage"
                }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, color = colors.hint, fontSize = 14.sp)
            }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible, key = { it.time }) { clip ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.key)
                        .clickable { service.insert(clip.text) }
                        .padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        clip.text,
                        color = colors.text,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    Box(
                        Modifier.size(44.dp).clickable { Store.removeClip(clip) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            null,
                            Modifier.size(18.dp),
                            tint = colors.hint,
                        )
                    }
                }
            }
        }
    }
}
