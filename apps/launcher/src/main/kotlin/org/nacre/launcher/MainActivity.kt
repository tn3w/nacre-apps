package org.nacre.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var widgets by mutableStateOf(false)
    var pendingWidget: Int? = null
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
            widgets -> widgets = false
            progress > 0f -> closeDrawer()
        }
    }

    fun home(alreadyHome: Boolean) {
        menu = null
        drag = null
        folder = null
        settings = false
        widgets = false
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
            removal.contains(position) && !drag.fromDrawer -> {
                Store.remove(drag.item.id)
            }

            dock.contains(position) -> {
                val cell = ((position.x - dock.left) / dock.width * columns).toInt()
                Store.drop(drag.item, DOCK, cell.coerceIn(0, columns - 1))
            }

            grid.contains(position) -> {
                val item = drag.item
                val x = (position.x - grid.left) / grid.width * columns
                val y = (position.y - grid.top) / grid.height * Store.rows
                val column = (x - (item.width - 1) / 2f).toInt()
                val row = (y - (item.height - 1) / 2f).toInt()
                val cell =
                    row.coerceIn(0, Store.rows - 1) * columns +
                        column.coerceIn(0, columns - 1)
                Store.drop(drag.item, page, cell)
            }
        }
    }
}

private const val CONFIGURE = 1

class MainActivity : ComponentActivity() {
    private val state: LauncherState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(clear, clear)
        window.isNavigationBarContrastEnforced = false
        onBackPressedDispatcher.addCallback(this) { state.back() }
        open(intent)
        val dark = resources.configuration.isNightModeActive
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Store.syncWallpaper(dark) } }
        setContent { NacreTheme { Launcher(state, ::addWidget) } }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Widgets.host.startListening() }
    }

    private val binding =
        registerForActivityResult(StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) configureWidget() else cancelWidget()
        }

    private fun addWidget(info: AppWidgetProviderInfo) {
        state.widgets = false
        val id = Widgets.allocate()
        state.pendingWidget = id
        if (Widgets.bind(id, info)) return configureWidget()
        binding.launch(Widgets.bindIntent(id, info))
    }

    private fun configureWidget() {
        val id = state.pendingWidget ?: return
        val info = Widgets.info(id) ?: return cancelWidget()
        if (info.configure == null) return placeWidget()
        runCatching {
            Widgets.host.startAppWidgetConfigureActivityForResult(
                this,
                id,
                0,
                CONFIGURE,
                null,
            )
        }.onFailure { cancelWidget() }
    }

    @Deprecated("AppWidgetHost reports configuration results only here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONFIGURE) return
        if (resultCode == RESULT_OK) placeWidget() else cancelWidget()
    }

    private fun placeWidget() {
        state.pendingWidget?.let(Widgets::place)
        state.pendingWidget = null
    }

    private fun cancelWidget() {
        state.pendingWidget?.let(Widgets::delete)
        state.pendingWidget = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val broughtToFront = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0
        state.home(alreadyHome = hasWindowFocus() && !broughtToFront)
        open(intent)
    }

    override fun onStop() {
        super.onStop()
        runCatching { Widgets.host.stopListening() }
        if (!isChangingConfigurations) state.reset()
    }

    private fun open(intent: Intent) {
        when (intent.getStringExtra("tab")) {
            "Apps" -> {
                state.progress = 1f
            }

            "Folder" -> {
                state.folder =
                    Store.items.value
                        .firstOrNull { it.kind == Kind.Folder }
                        ?.id
            }

            "Settings" -> {
                state.settings = true
            }
        }
    }
}

@Composable
fun Launcher(state: LauncherState, addWidget: (AppWidgetProviderInfo) -> Unit) {
    val items by Store.items.collectAsState()
    val pager =
        rememberPagerState {
            val used = items.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1
            used + if (state.drag != null) 1 else 0
        }
    val darkText = rememberDarkText()
    val dark = isSystemInDarkTheme()
    state.tone = if (darkText) Tone.Black else Tone.White

    LaunchedEffect(state.target) {
        val goal = state.target ?: return@LaunchedEffect
        animate(state.progress, if (goal) 1f else 0f) { value, _ ->
            state.progress = value
        }
        state.target = null
    }
    LaunchedEffect(state.firstPage) {
        if (state.firstPage == 0) return@LaunchedEffect
        pager.animateScrollToPage(0)
    }
    LaunchedEffect(pager.currentPage) { state.page = pager.currentPage }

    val activity = androidx.activity.compose.LocalActivity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val overlay = state.progress > 0.5f || state.settings || state.widgets
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        val light = if (overlay) !dark else darkText
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val motion =
            DrawerMotion(
                drag = { delta ->
                    state.progress =
                        (state.progress - delta / height).coerceIn(0f, 1f)
                },
                settle = { velocity ->
                    state.target =
                        when {
                            velocity < -800f -> true
                            velocity > 800f -> false
                            else -> state.progress > 0.5f
                        }
                },
            )
        Home(state, pager, darkText, motion)
        val drawerOffset =
            Modifier.offset {
                IntOffset(0, (height * (1 - state.progress)).roundToInt())
            }
        Drawer(state, motion, drawerOffset)
        state.folder?.let { id ->
            Store.folder(id)?.let { FolderView(it, state) }
                ?: run { state.folder = null }
        }
        state.menu?.let { ItemMenu(it, state) }
        state.drag?.takeIf { it.moved }?.let { DragShadow(it, state) }
        if (state.settings) SettingsScreen { state.settings = false }
        if (state.widgets) {
            WidgetPicker({ state.widgets = false }, addWidget) {
                state.widgets = false
                Store.addGlance()
            }
        }
    }
}

class DrawerMotion(val drag: (Float) -> Unit, val settle: (Float) -> Unit)

@Composable
private fun DragShadow(drag: Drag, state: LauncherState) {
    val half = with(LocalDensity.current) { (IconSize / 2).roundToPx() }
    Box(
        Modifier
            .offset {
                IntOffset(
                    drag.position.x.roundToInt() - half,
                    drag.position.y.roundToInt() - half,
                )
            }.scale(1.1f),
    ) {
        ItemIcon(drag.item, rememberVisual(drag.item, state.tone), tone = state.tone)
    }
}

class PinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps.getPinItemRequest(intent) }.getOrNull()
        if (request == null || !request.isValid) return finish()
        val density = resources.displayMetrics.densityDpi
        val shortcut = request.shortcutInfo
        val widget = request.getAppWidgetProviderInfo(this)
        val label =
            shortcut?.shortLabel?.toString()
                ?: widget?.loadLabel(packageManager)
                ?: return finish()
        val icon =
            runCatching {
                val drawable =
                    shortcut?.let { launcherApps.getShortcutIconDrawable(it, density) }
                        ?: widget?.loadIcon(this, density)
                drawable?.toBitmap()?.asImageBitmap()
            }.getOrNull()

        setContent {
            NacreTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text("Add to home screen?") },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            icon?.let { Image(it, null, Modifier.size(48.dp)) }
                            Text(label)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            accept(request)
                            finish()
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text("Cancel") } },
                )
            }
        }
    }

    private fun accept(request: LauncherApps.PinItemRequest) {
        if (!request.isValid) return
        val shortcut = request.shortcutInfo
        if (shortcut != null) {
            if (!request.accept()) return
            Store.add(
                Item(
                    id = Store.nextId(),
                    kind = Kind.Shortcut,
                    target = shortcut.`package`,
                    serial = Apps.serial(shortcut.userHandle),
                    shortcut = shortcut.id,
                ),
            )
            return
        }
        val id = Widgets.allocate()
        val extras = Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }
        if (request.accept(extras)) Widgets.place(id) else Widgets.delete(id)
    }
}
