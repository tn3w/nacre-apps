package org.nacre.launcher

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.SizeF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nacre.core.Palette

private const val MINUTE = 60_000L

@Composable
fun Home(
    state: LauncherState,
    pager: PagerState,
    darkText: Boolean,
    motion: DrawerMotion,
) {
    val items by Store.items.collectAsState()
    val columns by Store.columns.collectAsState()
    val context = LocalContext.current
    val color = if (darkText) Palette.Ink else Palette.Paper
    var pulled by remember { mutableFloatStateOf(0f) }
    val dragging = state.drag?.takeIf { it.moved }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .alpha(1f - state.progress)
                .systemBarsPadding()
                .draggable(
                    orientation = Orientation.Vertical,
                    state =
                        rememberDraggableState { delta ->
                            pulled += delta
                            if (state.progress > 0f || pulled < 0f) motion.drag(delta)
                        },
                    onDragStarted = { pulled = 0f },
                    onDragStopped = { velocity ->
                        if (pulled > 0f && state.progress == 0f) {
                            expandNotifications(context)
                        } else {
                            motion.settle(velocity)
                        }
                    },
                ),
        ) {
            HorizontalPager(
                pager,
                Modifier
                    .padding(top = 16.dp)
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        state.grid = it.boundsInRoot()
                        Widgets.cell =
                            Size(
                                state.grid.width / columns,
                                state.grid.height / Store.rows,
                            )
                    },
                beyondViewportPageCount = pager.pageCount,
                userScrollEnabled = state.drag == null,
                key = { it },
            ) { page ->
                Page(items.filter { it.page == page }, page, columns, state, color)
            }
            Dots(pager, color)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .onGloballyPositioned { state.dock = it.boundsInRoot() },
            ) {
                repeat(columns) { cell ->
                    Box(Modifier.weight(1f).fillMaxSize(), Alignment.Center) {
                        items.find { it.page == DOCK && it.cell == cell }?.let {
                            key(it.id) { HomeTile(it, state, null) }
                        }
                        if (dragging != null &&
                            hovers(state.dock, dragging.position, columns, 1) == cell
                        ) {
                            Highlight()
                        }
                    }
                }
            }
            SearchPill(color) {
                state.focusSearch = true
                state.openDrawer()
            }
        }
        if (dragging != null && !dragging.fromDrawer) {
            RemoveZone(color, state.removal.contains(dragging.position)) {
                state.removal = it
            }
        }
    }
    EdgeScroll(state, pager)
}

private fun hovers(area: Rect, position: Offset, columns: Int, rows: Int): Int? {
    if (!area.contains(position)) return null
    val column = ((position.x - area.left) / area.width * columns).toInt()
    val row = ((position.y - area.top) / area.height * rows).toInt()
    return row * columns + column
}

@Composable
private fun Page(
    items: List<Item>,
    page: Int,
    columns: Int,
    state: LauncherState,
    color: Color,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { position ->
                    val at = state.grid.topLeft + position
                    val cell = hovers(state.grid, at, columns, Store.rows)
                    if (cell != null &&
                        Store.occupied(page, cell)
                    ) {
                        return@detectTapGestures
                    }
                    state.menu = Menu(Rect(at, Size(1f, 1f)))
                })
            },
    ) {
        val rows = Store.rows
        val width = maxWidth / columns
        val height = maxHeight / rows
        items.forEach { item ->
            val place =
                Modifier
                    .offset(width * (item.cell % columns), height * (item.cell / columns))
                    .size(width * item.width, height * item.height)
            key(item.id) {
                if (item.isWidget) {
                    WidgetTile(item, state, color, place)
                } else {
                    HomeTile(item, state, color, place)
                }
            }
        }
        val drag = state.drag?.takeIf { it.moved && state.page == page }
        val cell = drag?.let { hovers(state.grid, it.position, columns, rows) }
        if (cell != null) {
            Box(
                Modifier
                    .offset(width * (cell % columns), height * (cell / columns))
                    .size(width, height),
                Alignment.Center,
            ) { Highlight() }
        }
    }
}

@Composable
private fun Highlight() {
    Box(
        Modifier
            .size(IconSize + 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    )
}

@Composable
fun HomeTile(
    item: Item,
    state: LauncherState,
    labelColor: Color?,
    modifier: Modifier = Modifier,
) {
    val launch = rememberLaunch()
    val slop = LocalViewConfiguration.current.touchSlop * 2
    val hidden = state.drag?.let { it.moved && it.item.id == item.id } == true
    Tile(
        item,
        labelColor,
        modifier.alpha(if (hidden) 0f else 1f),
        state.tone,
        open = { visual, bounds ->
            when (item.kind) {
                Kind.Folder -> state.folder = item.id
                else -> launch(visual, bounds)
            }
        },
        hold = { visual, bounds ->
            state.menu = Menu(bounds, item, visual.app)
            state.drag = Drag(item, fromDrawer = false, start = bounds.center)
        },
        move = { state.follow(it, slop) },
        release = state::release,
    )
}

@Composable
private fun Dots(pager: PagerState, color: Color) {
    Row(
        Modifier.fillMaxWidth().height(16.dp),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        Alignment.CenterVertically,
    ) {
        if (pager.pageCount < 2) return@Row
        repeat(pager.pageCount) {
            val alpha = if (it == pager.currentPage) 1f else 0.4f
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun SearchPill(color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(16.dp, 4.dp, 16.dp, 12.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = color)
        Text("Search apps", color = color.copy(alpha = 0.8f))
    }
}

@Composable
private fun RemoveZone(color: Color, active: Boolean, place: (Rect) -> Unit) {
    val shade = if (color == Palette.Paper) Color.Black else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(shade.copy(alpha = 0.6f), Color.Transparent),
                ),
            ).statusBarsPadding()
            .height(96.dp)
            .onGloballyPositioned { place(it.boundsInRoot()) }
            .alpha(if (active) 1f else 0.7f),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_close), null, tint = color)
        Text("Remove", color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Glance(color: Color) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(MINUTE - value % MINUTE)
            value = System.currentTimeMillis()
        }
    }
    val alarm = rememberNextAlarm()
    val shadow =
        Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
            .takeIf { color == Palette.Paper }
    val style = TextStyle(color = color, shadow = shadow)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        val flags =
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_NO_YEAR
        Text(
            DateUtils.formatDateTime(context, now, flags),
            Modifier.clickable {
                context.openSafely(
                    Intent.makeMainSelectorActivity(
                        Intent.ACTION_MAIN,
                        Intent.CATEGORY_APP_CALENDAR,
                    ),
                )
            },
            style = MaterialTheme.typography.headlineSmall.merge(style),
        )
        alarm?.let { next ->
            val time =
                DateUtils.formatDateTime(
                    context,
                    next.triggerTime,
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
                        DateUtils.FORMAT_ABBREV_WEEKDAY,
                )
            Row(
                Modifier.padding(top = 6.dp).clickable { showAlarm(next) },
                Arrangement.spacedBy(6.dp),
                Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_alarm),
                    null,
                    Modifier.size(16.dp),
                    tint = color,
                )
                Text(time, style = MaterialTheme.typography.bodyMedium.merge(style))
            }
        }
    }
}

private fun showAlarm(alarm: AlarmManager.AlarmClockInfo) {
    val options =
        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
        )
    runCatching { alarm.showIntent?.send(options.toBundle()) }
}

@Composable
private fun rememberNextAlarm(): AlarmManager.AlarmClockInfo? {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AlarmManager::class.java) }
    var next by remember { mutableStateOf(manager.nextAlarmClock) }
    DisposableEffect(manager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    next = manager.nextAlarmClock
                }
            }
        val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return next
}

@Composable
fun rememberDarkText(): Boolean {
    val context = LocalContext.current
    val manager = remember { WallpaperManager.getInstance(context) }

    fun read(): Boolean {
        val hints =
            manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: 0
        return hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }
    var dark by remember { mutableStateOf(read()) }
    DisposableEffect(manager) {
        val listener =
            WallpaperManager.OnColorsChangedListener { _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) dark = read()
            }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return dark
}

@Composable
private fun EdgeScroll(state: LauncherState, pager: PagerState) {
    val edge = with(LocalDensity.current) { 28.dp.toPx() }
    val drag = state.drag?.takeIf { it.moved }
    val grid = state.grid
    val direction =
        when {
            drag == null || drag.position.y !in grid.top..grid.bottom -> 0
            drag.position.x < grid.left + edge -> -1
            drag.position.x > grid.right - edge -> 1
            else -> 0
        }
    LaunchedEffect(direction) {
        while (direction != 0) {
            delay(600)
            val target = (pager.currentPage + direction).coerceIn(0, pager.pageCount - 1)
            pager.animateScrollToPage(target)
        }
    }
}

private fun expandNotifications(context: Context) {
    runCatching {
        val service = context.getSystemService("statusbar") ?: return
        service.javaClass.getMethod("expandNotificationsPanel").invoke(service)
    }
}

@Composable
fun ItemMenu(menu: Menu, state: LauncherState) {
    val context = LocalContext.current
    val view = LocalView.current
    val launch = rememberLaunch()
    val hidden by Store.hidden.collectAsState()
    val app = menu.app
    val item = menu.item
    val shortcuts by produceState(emptyList<MenuEntry>(), app) {
        value =
            withContext(Dispatchers.IO) {
                app?.let(Apps::shortcuts).orEmpty().map { shortcut ->
                    MenuEntry(
                        shortcut.shortLabel?.toString().orEmpty(),
                        image = Apps.icon(shortcut),
                    ) { launch(Visual("", null, shortcut = shortcut), menu.bounds) }
                }
            }
    }
    val rect = menu.bounds.toAndroidRectF()
    val area =
        android.graphics.Rect(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
    val actions =
        buildList {
            if (item == null) {
                add(
                    MenuEntry("Wallpaper", R.drawable.ic_image) {
                        context.openSafely(Intent(Intent.ACTION_SET_WALLPAPER))
                    },
                )
                add(MenuEntry("Widgets", R.drawable.ic_widgets) { state.widgets = true })
                add(
                    MenuEntry("Home settings", R.drawable.ic_settings) {
                        state.settings = true
                    },
                )
                return@buildList
            }
            app?.let {
                add(MenuEntry("App info", R.drawable.ic_info) { Apps.showInfo(area, it) })
            }
            when {
                menu.folder != null -> {
                    add(
                        MenuEntry("Move to home screen", R.drawable.ic_add) {
                            Store.removeFromFolder(menu.folder, item.id, toHome = true)
                        },
                    )
                    add(
                        MenuEntry("Remove", R.drawable.ic_close) {
                            Store.removeFromFolder(menu.folder, item.id, toHome = false)
                        },
                    )
                }

                menu.fromDrawer && app != null -> {
                    if (!Store.has(app)) {
                        add(
                            MenuEntry("Add to home screen", R.drawable.ic_add) {
                                Store.add(item)
                            },
                        )
                    }
                    val isHidden = app.key in hidden
                    add(
                        MenuEntry(
                            if (isHidden) "Show in app list" else "Hide from app list",
                            R.drawable.ic_hide,
                        ) {
                            Store.setHidden(app, !isHidden)
                        },
                    )
                }

                else -> {
                    add(
                        MenuEntry("Remove", R.drawable.ic_close) {
                            Store.remove(item.id)
                        },
                    )
                }
            }
            app?.takeIf { !Apps.isSystem(it) }?.let {
                add(MenuEntry("Uninstall", R.drawable.ic_delete) { Apps.uninstall(it) })
            }
        }
    MenuPopup(menu.bounds, shortcuts, actions) { state.menu = null }
}

@Composable
fun FolderView(folder: Item, state: LauncherState) {
    val launch = rememberLaunch()
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    val tone = if (isSystemInDarkTheme()) Tone.White else Tone.Black
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures { state.folder = null } },
        Alignment.Center,
    ) {
        Surface(
            Modifier
                .padding(24.dp)
                .widthIn(max = 380.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp)) {
                TextField(
                    value = name,
                    onValueChange = {
                        name = it.take(40)
                        Store.rename(folder.id, name)
                    },
                    placeholder = { Text("Folder") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                )
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 440.dp)) {
                    items(folder.children, key = { it.id }) { child ->
                        Tile(
                            child,
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.padding(vertical = 8.dp),
                            tone,
                            open = launch,
                            hold = { visual, bounds ->
                                state.menu =
                                    Menu(bounds, child, visual.app, folder = folder.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetTile(
    item: Item,
    state: LauncherState,
    color: Color,
    modifier: Modifier,
) {
    val slop = LocalViewConfiguration.current.touchSlop * 2
    val info = remember(item.widget) {
        if (item.kind == Kind.Widget) Widgets.info(item.widget) else null
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val hidden = state.drag?.let { it.moved && it.item.id == item.id } == true
    BoxWithConstraints(
        modifier
            .alpha(if (hidden) 0f else 1f)
            .padding(8.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .press(
                tap = null,
                hold = {
                    state.menu = Menu(bounds, item)
                    state.drag = Drag(item, fromDrawer = false, start = bounds.center)
                },
                move = { state.follow(it, slop) },
                release = state::release,
            ),
    ) {
        if (item.kind == Kind.Glance) return@BoxWithConstraints Glance(color)
        if (info == null) return@BoxWithConstraints
        val size = SizeF(maxWidth.value, maxHeight.value)
        AndroidView(
            factory = { Widgets.host.createView(it, item.widget, info) },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
            update = { it.updateAppWidgetSize(Bundle(), listOf(size)) },
        )
    }
}
