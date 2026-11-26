package org.nacre.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val IconSize = 56.dp

enum class Tone { Black, White }

private val GLYPHS = mapOf(
    "org.nacre.calculator" to
        (R.drawable.glyph_black_calculator to R.drawable.glyph_white_calculator),
    "org.nacre.clock" to (R.drawable.glyph_black_clock to R.drawable.glyph_white_clock),
    "org.nacre.contacts" to
        (R.drawable.glyph_black_contacts to R.drawable.glyph_white_contacts),
    "org.nacre.messages" to
        (R.drawable.glyph_black_messages to R.drawable.glyph_white_messages),
    "org.nacre.phone" to (R.drawable.glyph_black_phone to R.drawable.glyph_white_phone),
    "com.android.settings" to
        (R.drawable.glyph_black_settings to R.drawable.glyph_white_settings),
)

private fun glyph(item: Item, tone: Tone?): Int? {
    if (tone == null || item.kind != Kind.App) return null
    val (black, white) = GLYPHS[item.target.substringBefore('/')] ?: return null
    return if (tone == Tone.White) white else black
}

data class Visual(
    val label: String,
    val icon: ImageBitmap?,
    val app: App? = null,
    val shortcut: ShortcutInfo? = null,
    val dimmed: Boolean = false,
)

@Composable
fun rememberVisual(item: Item): Visual? {
    val revision by Apps.revision.collectAsState()
    val themed by Store.themed.collectAsState()
    val dark = isSystemInDarkTheme()
    return produceState<Visual?>(null, item, revision, themed, dark) {
        value = withContext(Dispatchers.IO) { visual(item, themed, dark) }
    }.value
}

private fun visual(item: Item, themed: Boolean, dark: Boolean): Visual? {
    val dimmed = Apps.isQuiet(item.serial)
    return when (item.kind) {
        Kind.App -> Apps.find(item.target, item.serial)?.let {
            Visual(it.label, Apps.icon(it, themed, dark), app = it, dimmed = dimmed)
        }

        Kind.Shortcut -> Apps.pinned(item.target, item.shortcut, item.serial)?.let {
            val icon = Apps.icon(it, themed, dark)
            Visual(it.shortLabel.toString(), icon, shortcut = it, dimmed = dimmed)
        }

        Kind.Folder -> Visual(item.name.ifBlank { "Folder" }, null)
    }
}

@Composable
fun ItemIcon(item: Item, visual: Visual?, size: Dp = IconSize, tone: Tone? = null) {
    if (item.kind == Kind.Folder) return FolderIcon(item, size, tone)
    glyph(item, tone)?.let {
        return Image(painterResource(it), null, Modifier.size(size))
    }
    val icon = visual?.icon ?: return Box(Modifier.size(size))
    Image(icon, null, Modifier.size(size).alpha(if (visual.dimmed) 0.5f else 1f))
}

@Composable
private fun FolderIcon(folder: Item, size: Dp, tone: Tone?) {
    val background = when (tone) {
        Tone.White -> Color.White.copy(alpha = 0.16f)
        Tone.Black -> Color.Black.copy(alpha = 0.08f)
        null -> MaterialTheme.colorScheme.surfaceContainer
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3.5f))
            .background(background)
            .padding(size / 12),
    ) {
        Column(
            Modifier.fillMaxSize(),
            Arrangement.Center,
            Alignment.CenterHorizontally,
        ) {
            folder.children.take(4).chunked(2).forEach { pair ->
                Row {
                    pair.forEach { ItemIcon(it, rememberVisual(it), size * 5 / 12, tone) }
                }
            }
        }
    }
}

@Composable
fun Tile(
    item: Item,
    labelColor: Color?,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    open: (Visual, Rect) -> Unit,
    hold: (Visual, Rect) -> Unit,
    move: (Offset) -> Unit = {},
    release: () -> Unit = {},
) {
    val visual = rememberVisual(item)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val label = visual?.label.orEmpty()
    Column(
        modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .press(
                tap = { visual?.let { open(it, bounds) } },
                hold = { visual?.let { hold(it, bounds) } },
                move = move,
                release = release,
            ).semantics {
                contentDescription = label
                onClick {
                    visual?.let { open(it, bounds) }
                    true
                }
                onLongClick {
                    visual?.let { hold(it, bounds) }
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ItemIcon(item, visual, tone = tone)
        labelColor?.let { Label(label, it) }
    }
}

@Composable
fun Label(text: String, color: Color) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
        .takeIf { color.red > 0.5f }
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium.merge(TextStyle(shadow = shadow)),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Modifier.press(
    tap: () -> Unit,
    hold: () -> Unit,
    move: (Offset) -> Unit,
    release: () -> Unit,
): Modifier {
    val onTap by rememberUpdatedState(tap)
    val onHold by rememberUpdatedState(hold)
    val onMove by rememberUpdatedState(move)
    val onRelease by rememberUpdatedState(release)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            var up: PointerInputChange? = null
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                up = waitForUpOrCancellation()
            }
            if (released != null) {
                up?.let {
                    it.consume()
                    onTap()
                }
                return@awaitEachGesture
            }
            onHold()
            drag(down.id) {
                onMove(it.positionChange())
                it.consume()
            }
            onRelease()
        }
    }
}

class MenuEntry(
    val label: String,
    val icon: Int? = null,
    val image: ImageBitmap? = null,
    val action: () -> Unit,
)

private class Anchor(val bounds: IntRect, val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val x = (bounds.center.x - popupContentSize.width / 2).coerceIn(gap, maxX)
        val above = bounds.top - popupContentSize.height - gap
        val y = if (above > gap * 4) above else bounds.bottom + gap
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y.coerceIn(0, maxY))
    }
}

@Composable
fun MenuPopup(
    bounds: Rect,
    shortcuts: List<MenuEntry>,
    actions: List<MenuEntry>,
    dismiss: () -> Unit,
) {
    val rect = bounds.toAndroidRectF()
    val anchor = Anchor(
        IntRect(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        ),
        with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() },
    )
    Popup(anchor, dismiss, PopupProperties(focusable = true)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp,
            modifier = Modifier.width(232.dp),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                shortcuts.forEach { MenuRow(it, dismiss) }
                if (shortcuts.isNotEmpty() && actions.isNotEmpty()) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 6.dp, horizontal = 16.dp),
                    )
                }
                actions.forEach { MenuRow(it, dismiss) }
            }
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, dismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                dismiss()
                entry.action()
            }.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entry.icon?.let { Icon(painterResource(it), null, Modifier.size(20.dp)) }
        entry.image?.let { Image(it, null, Modifier.size(20.dp)) }
        Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun Context.openSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Apps.toast("No app can do this") }
}

@Composable
fun rememberLaunch(): (Visual, Rect) -> Unit {
    val view = LocalView.current
    return { visual, bounds ->
        val rect = bounds.toAndroidRectF()
        val area = android.graphics.Rect(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
        visual.app?.let { Apps.launch(view, area, it) }
        visual.shortcut?.let { Apps.launch(view, area, it) }
    }
}
