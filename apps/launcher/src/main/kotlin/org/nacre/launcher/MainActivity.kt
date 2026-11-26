package org.nacre.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import kotlin.math.roundToInt
import org.nacre.core.NacreTheme

class Drag(val item: Item, val fromDrawer: Boolean, start: Offset) {
    var position by mutableStateOf(start)
    var moved by mutableStateOf(false)
    val start = start
}

class Menu(
    val bounds: Rect,
    val item: Item? = null,
    val app: App? = null,
    val fromDrawer: Boolean = false,
    val folder: Long? = null,
)

class LauncherState : ViewModel() {
    var progress by mutableFloatStateOf(0f)
    var target by mutableStateOf<Boolean?>(null)
    var query by mutableStateOf("")
    var drag by mutableStateOf<Drag?>(null)
    var menu by mutableStateOf<Menu?>(null)
    var folder by mutableStateOf<Long?>(null)
    var settings by mutableStateOf(false)
    var firstPage by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var grid = Rect.Zero
    var dock = Rect.Zero
    var removal = Rect.Zero

    var focusSearch by mutableStateOf(false)
    var tone by mutableStateOf(Tone.White)

    fun follow(delta: Offset, slop: Float) {
        val drag = drag ?: return
        drag.position += delta
        if (drag.moved || (drag.position - drag.start).getDistance() < slop) return
        drag.moved = true
        menu = null
        if (drag.fromDrawer) closeDrawer()
    }

    fun release() {
        drag?.takeIf { it.moved }?.let(::drop)
        drag = null
    }

    fun openDrawer() {
        target = true
    }

    fun closeDrawer() {
        target = false
    }

    fun back() {
        when {
            menu != null -> menu = null
            folder != null -> folder = null
            settings -> settings = false
            progress > 0f -> closeDrawer()
        }
    }

    fun home(alreadyHome: Boolean) {
        menu = null
        drag = null
        folder = null
        settings = false
        if (!alreadyHome) return reset()
        if (progress > 0f) closeDrawer() else firstPage++
    }

    fun reset() {
        progress = 0f
        target = null
        query = ""
        menu = null
        drag = null
    }

    fun drop(drag: Drag) {
        val position = drag.position
        val columns = Store.columns.value
        when {
            removal.contains(position) && !drag.fromDrawer -> Store.remove(drag.item.id)
            dock.contains(position) -> {
                val cell = ((position.x - dock.left) / dock.width * columns).toInt()
                Store.drop(drag.item, DOCK, cell.coerceIn(0, columns - 1))
            }
            grid.contains(position) -> {
                val column = ((position.x - grid.left) / grid.width * columns).toInt()
                val row = ((position.y - grid.top) / grid.height * Store.rows).toInt()
                val cell = row.coerceIn(0, Store.rows - 1) * columns + column.coerceIn(0, columns - 1)
                Store.drop(drag.item, page, cell)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val state: LauncherState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(clear, clear)
        window.isNavigationBarContrastEnforced = false
        onBackPressedDispatcher.addCallback(this) { state.back() }
        open(intent)
        setContent { NacreTheme { Launcher(state) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val broughtToFront = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0
        state.home(alreadyHome = hasWindowFocus() && !broughtToFront)
        open(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) state.reset()
    }

    private fun open(intent: Intent) {
        when (intent.getStringExtra("tab")) {
            "Apps" -> state.progress = 1f
            "Folder" -> state.folder = Store.items.value.firstOrNull { it.kind == Kind.Folder }?.id
            "Settings" -> state.settings = true
        }
    }
}

@Composable
fun Launcher(state: LauncherState) {
    val items by Store.items.collectAsState()
    val pager = rememberPagerState {
        val used = items.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1
        used + if (state.drag != null) 1 else 0
    }
    val darkText = rememberDarkText()
    val dark = isSystemInDarkTheme()
    state.tone = if (darkText) Tone.Black else Tone.White

    LaunchedEffect(state.target) {
        val goal = state.target ?: return@LaunchedEffect
        animate(state.progress, if (goal) 1f else 0f) { value, _ -> state.progress = value }
        state.target = null
    }
    LaunchedEffect(state.firstPage) { if (state.firstPage > 0) pager.animateScrollToPage(0) }
    LaunchedEffect(pager.currentPage) { state.page = pager.currentPage }

    val activity = androidx.activity.compose.LocalActivity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val overlay = state.progress > 0.5f || state.settings
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val light = if (overlay) !dark else darkText
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val motion = DrawerMotion(
            drag = { delta -> state.progress = (state.progress - delta / height).coerceIn(0f, 1f) },
            settle = { velocity ->
                state.target = when {
                    velocity < -800f -> true
                    velocity > 800f -> false
                    else -> state.progress > 0.5f
                }
            },
        )
        Home(state, pager, darkText, motion)
        Drawer(state, motion, Modifier.offset { IntOffset(0, ((1 - state.progress) * height).roundToInt()) })
        state.folder?.let { id -> Store.folder(id)?.let { FolderView(it, state) } ?: run { state.folder = null } }
        state.menu?.let { ItemMenu(it, state) }
        state.drag?.takeIf { it.moved }?.let { DragShadow(it, state) }
        if (state.settings) SettingsScreen { state.settings = false }
    }
}

class DrawerMotion(val drag: (Float) -> Unit, val settle: (Float) -> Unit)

@Composable
private fun DragShadow(drag: Drag, state: LauncherState) {
    val half = with(LocalDensity.current) { (IconSize / 2).roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(drag.position.x.roundToInt() - half, drag.position.y.roundToInt() - half) }
            .scale(1.1f),
    ) {
        ItemIcon(drag.item, rememberVisual(drag.item), tone = state.tone)
    }
}

class PinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        val shortcut = request?.shortcutInfo
        if (request?.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ||
            shortcut == null || !request.isValid
        ) {
            return finish()
        }
        val icon = runCatching {
            launcherApps.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
                ?.toBitmap()?.asImageBitmap()
        }.getOrNull()

        setContent {
            NacreTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text("Add to home screen?") },
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            icon?.let { Image(it, null, Modifier.size(48.dp)) }
                            Text(shortcut.shortLabel?.toString().orEmpty())
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (request.isValid && request.accept()) {
                                Store.add(
                                    Item(
                                        id = Store.nextId(),
                                        kind = Kind.Shortcut,
                                        target = shortcut.`package`,
                                        serial = Apps.serial(shortcut.userHandle),
                                        shortcut = shortcut.id,
                                    ),
                                )
                            }
                            finish()
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
                )
            }
        }
    }
}
