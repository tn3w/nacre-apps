package org.nacre.launcher

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

@Composable
fun Drawer(state: LauncherState, motion: DrawerMotion, modifier: Modifier) {
    val apps by Apps.apps.collectAsState()
    val profiles by Apps.profiles.collectAsState()
    val hidden by Store.hidden.collectAsState()
    val columns by Store.columns.collectAsState()
    val keyboard by Store.keyboard.collectAsState()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val launch = rememberLaunch()
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    val open = state.progress == 1f
    val closed = state.progress == 0f

    LaunchedEffect(open) {
        if (open && (keyboard || state.focusSearch)) focus.requestFocus()
        if (!open) focusManager.clearFocus()
    }
    LaunchedEffect(closed) {
        if (!closed) return@LaunchedEffect
        state.query = ""
        state.focusSearch = false
        grid.scrollToItem(0)
    }

    val query = state.query.trim().searchable()
    val results = if (query.isEmpty()) emptyList() else search(apps, query)
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || state.progress == 1f) return Offset.Zero
            motion.drag(available.y)
            return Offset(0f, available.y)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            motion.drag(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (state.progress == 1f) return Velocity.Zero
            motion.settle(available.y)
            return available
        }
    }

    Surface(
        modifier.fillMaxSize(),
    ) {
        Column(Modifier.statusBarsPadding()) {
            TextField(
                value = state.query,
                onValueChange = { state.query = it.take(60) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp)
                    .focusRequester(focus)
                    .onGloballyPositioned { fieldBounds = it.boundsInRoot() }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState(motion.drag),
                        onDragStopped = { motion.settle(it) },
                    ),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { state.query = "" }) {
                            Icon(painterResource(R.drawable.ic_close), "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    results.firstOrNull()?.let { app ->
                        launch(Visual(app.label, null, app = app), fieldBounds)
                    }
                }),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                Modifier
                    .weight(1f)
                    .nestedScroll(connection)
                    .navigationBarsPadding()
                    .imePadding(),
                grid,
            ) {
                if (query.isNotEmpty()) {
                    apps(results, state)
                    if (results.isEmpty()) wide("empty") { Note("No apps found") }
                    return@LazyVerticalGrid
                }
                profiles.sortedBy { !it.main }.forEach { profile ->
                    if (!profile.main) wide("profile${profile.serial}") { ProfileHeader(profile) }
                    if (profile.hidesApps) return@forEach
                    apps(apps.filter { it.serial == profile.serial && it.key !in hidden }, state)
                }
            }
        }
    }
}

private fun search(apps: List<App>, query: String): List<App> {
    val words = { app: App -> app.searchable.split(' ', '-', '.') }
    return apps
        .filter { Apps.profile(it.serial)?.hidesApps != true && query in it.searchable }
        .sortedBy { app -> words(app).none { it.startsWith(query) } }
}

private fun LazyGridScope.wide(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.apps(apps: List<App>, state: LauncherState) {
    items(apps, key = { it.key }) { DrawerTile(it, state) }
}

@Composable
private fun DrawerTile(app: App, state: LauncherState) {
    val launch = rememberLaunch()
    val slop = LocalViewConfiguration.current.touchSlop * 2
    val item = remember(app.key) { Item.of(app, 0) }
    Tile(
        item,
        MaterialTheme.colorScheme.onSurface,
        Modifier.padding(vertical = 10.dp),
        open = launch,
        hold = { _, bounds ->
            val placed = item.copy(id = Store.nextId())
            state.menu = Menu(bounds, placed, app, fromDrawer = true)
            state.drag = Drag(placed, fromDrawer = true, start = bounds.center)
        },
        move = { state.follow(it, slop) },
        release = state::release,
    )
}

@Composable
private fun ProfileHeader(profile: Profile) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp, 24.dp, 24.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(profile.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = !profile.quiet, onCheckedChange = { Apps.setActive(profile.user, it) })
    }
}

@Composable
fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        color = MaterialTheme.colorScheme.outline,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
