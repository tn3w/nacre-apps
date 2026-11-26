package org.nacre.launcher

import android.app.role.RoleManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(close: () -> Unit) {
    var hiddenApps by remember { mutableStateOf(false) }
    val back = { if (hiddenApps) hiddenApps = false else close() }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) {
                    Icon(painterResource(R.drawable.ic_back), "Back")
                }
                Text(
                    if (hiddenApps) "Hidden apps" else "Home settings",
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (hiddenApps) HiddenApps() else Options { hiddenApps = true }
        }
    }
}

@Composable
private fun Options(showHidden: () -> Unit) {
    val context = LocalContext.current
    val roles = remember { context.getSystemService(RoleManager::class.java) }
    var isHome by remember { mutableStateOf(roles.isRoleHeld(RoleManager.ROLE_HOME)) }
    val request =
        rememberLauncherForActivityResult(StartActivityForResult()) {
            isHome = roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
    val columns by Store.columns.collectAsState()
    val keyboard by Store.keyboard.collectAsState()
    val sheen by Store.sheen.collectAsState()
    val autoAdd by Store.autoAdd.collectAsState()
    val hidden by Store.hidden.collectAsState()

    LazyColumn {
        if (!isHome) {
            item {
                Option("Set as default home app", "Use Launcher when you press home") {
                    request.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }
            }
        }
        item {
            Option("Grid", "$columns × ${columns + 1}") {}
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 24.dp)) {
                listOf(4, 5).forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = columns == count,
                        onClick = { Store.setColumns(count) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text("$count columns") }
                }
            }
        }
        item {
            Toggle(
                "Sheen icons",
                "Render every app icon in the liquid chrome style",
                sheen,
                Store::setSheen,
            )
        }
        item {
            Toggle(
                "Show keyboard",
                "Start typing when the app list opens",
                keyboard,
                Store::setKeyboard,
            )
        }
        item {
            Toggle(
                "Add new apps",
                "Place newly installed apps on the home screen",
                autoAdd,
                Store::setAutoAdd,
            )
        }
        item {
            val count = if (hidden.isEmpty()) "None" else "${hidden.size} hidden"
            Option("Hidden apps", count, showHidden)
        }
    }
}

@Composable
private fun HiddenApps() {
    val apps by Apps.apps.collectAsState()
    val hidden by Store.hidden.collectAsState()
    LazyColumn {
        items(apps, key = { it.key }) { app ->
            val icon by produceState(
                null as androidx.compose.ui.graphics.ImageBitmap?,
                app,
            ) {
                value = withContext(Dispatchers.IO) { Apps.icon(app) }
            }
            val profile = Apps.profile(app.serial)?.takeIf { !it.main }?.label
            ListItem(
                headlineContent = { Text(app.label) },
                supportingContent = profile?.let { { Text(it) } },
                leadingContent = { icon?.let { Image(it, null, Modifier.size(40.dp)) } },
                trailingContent = {
                    Checkbox(app.key in hidden, { Store.setHidden(app, it) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier.clickable {
                        Store.setHidden(app, app.key !in hidden)
                    },
            )
        }
    }
}

@Composable
private fun Option(title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun Toggle(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 8.dp),
    )
}
