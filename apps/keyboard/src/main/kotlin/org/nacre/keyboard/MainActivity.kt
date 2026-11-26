package org.nacre.keyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.nacre.core.NacreTheme

enum class Tab(
    val label: String,
    val icon: Int,
) {
    Languages("Languages", R.drawable.ic_language),
    Typing("Typing", R.drawable.ic_keyboard),
    Words("Words", R.drawable.ic_words),
    Try("Try", R.drawable.ic_edit),
    ;

    companion object {
        fun named(name: String?) = entries.find { it.name == name }
    }
}

class MainActivity : ComponentActivity() {
    private val tab = mutableStateOf(Tab.Languages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        val saved = savedInstanceState?.getString("tab") ?: intent.getStringExtra("tab")
        Tab.named(saved)?.let { tab.value = it }
        setContent { NacreTheme { SettingsApp(tab) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Tab.named(intent.getStringExtra("tab"))?.let { tab.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }
}

@Composable
fun SettingsApp(tab: MutableState<Tab>) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab.value == it,
                        onClick = { tab.value = it },
                        icon = { Icon(painterResource(it.icon), null) },
                        label = { Text(it.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).imePadding()) {
            when (tab.value) {
                Tab.Languages -> LanguagesScreen()
                Tab.Typing -> TypingScreen()
                Tab.Words -> WordsScreen()
                Tab.Try -> TryScreen()
            }
        }
    }
}

@Composable
private fun Screen(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                title,
                Modifier.padding(20.dp, 24.dp, 20.dp, 12.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        content()
    }
}

@Composable
private fun Setting(
    title: String,
    detail: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 8.dp),
    ) { Text(title) }
}

private enum class Status { Disabled, Inactive, Ready }

private fun status(context: Context): Status {
    val manager = context.getSystemService(InputMethodManager::class.java)
    val ours =
        manager.enabledInputMethodList.find { it.packageName == context.packageName }
            ?: return Status.Disabled
    val current =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
    return if (current == ours.id) Status.Ready else Status.Inactive
}

@Composable
private fun LanguagesScreen() {
    val context = LocalContext.current
    val languages by Store.languages.collectAsState()
    var status by remember { mutableStateOf(Status.Ready) }
    LifecycleResumeEffect(Unit) {
        status = status(context)
        onPauseOrDispose {}
    }
    Screen("Languages") {
        if (status != Status.Ready) item { SetupCard(status) }
        items(languages + (Language.entries - languages.toSet())) { language ->
            val index = languages.indexOf(language)
            ListItem(
                leadingContent = {
                    Checkbox(index >= 0, null)
                },
                supportingContent = {
                    Text(
                        if (index ==
                            0
                        ) {
                            "Active · ${layoutName(language)}"
                        } else {
                            layoutName(language)
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier
                        .clickable {
                            Store.setLanguages(
                                if (index >=
                                    0
                                ) {
                                    languages - language
                                } else {
                                    languages + language
                                },
                            )
                        }.padding(horizontal = 8.dp),
            ) { Text(language.title) }
        }
        item {
            Text(
                "Tap the globe key or long-press space to switch. Word lists stay on " +
                    "this device.",
                Modifier.padding(24.dp, 12.dp),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun layoutName(language: Language) =
    language.rows
        .first()
        .take(6)
        .uppercase()

@Composable
private fun SetupCard(status: Status) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (status ==
                    Status.Disabled
                ) {
                    "Turn on Keyboard"
                } else {
                    "Switch to Keyboard"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (status == Status.Disabled) {
                    "Enable Keyboard in the system keyboard list to start typing with it."
                } else {
                    "Keyboard is on but another keyboard is in use."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                if (status == Status.Disabled) {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                } else {
                    context
                        .getSystemService(InputMethodManager::class.java)
                        .showInputMethodPicker()
                }
            }) {
                Text(
                    if (status ==
                        Status.Disabled
                    ) {
                        "Open settings"
                    } else {
                        "Choose keyboard"
                    },
                )
            }
        }
    }
}

@Composable
private fun TypingScreen() {
    val toggles by Store.toggles.collectAsState()
    val height by Store.height.collectAsState()
    Screen("Typing") {
        items(Toggle.entries) { toggle ->
            Setting(toggle.title, toggle.summary, toggles[toggle] ?: toggle.default) {
                Store[toggle] = it
            }
        }
        item {
            Column(
                Modifier.padding(24.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Keyboard height", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Height.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = height == option,
                            onClick = { Store.setHeight(option) },
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index,
                                    Height.entries.size,
                                ),
                        ) { Text(option.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordsScreen() {
    val words by Store.words.collectAsState()
    var adding by remember { mutableStateOf("") }
    var clearing by remember { mutableStateOf(false) }
    val trusted = words.filterValues { it >= 2 }.keys.sortedBy(String::lowercase)
    Screen("Words") {
        item {
            Row(
                Modifier.padding(16.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    adding,
                    { value -> adding = value.filterNot(Char::isWhitespace).take(48) },
                    Modifier.weight(1f),
                    placeholder = { Text("Add a word") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        Store.learn(adding, explicit = true)
                        adding = ""
                    },
                    enabled = adding.any(Char::isLetter),
                ) { Text("Add") }
            }
        }
        if (trusted.isEmpty()) {
            item {
                Text(
                    "Words you add or keep typing appear here and are never " +
                        "auto-corrected.",
                    Modifier.padding(24.dp, 16.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        items(trusted, key = { it }) { word ->
            ListItem(
                trailingContent = {
                    IconButton(onClick = { Store.forget(word) }) {
                        Icon(painterResource(R.drawable.ic_delete), "Remove")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(word) }
        }
        item {
            OutlinedButton({ clearing = true }, Modifier.padding(24.dp, 16.dp)) {
                Text("Clear typing history")
            }
        }
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear typing history?") },
            text = {
                Text(
                    "Removes learned words, next-word predictions and recent emoji.",
                )
            },
            confirmButton = {
                TextButton({
                    Store.clearHistory()
                    clearing = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton({ clearing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TryScreen() {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Try it",
            Modifier.padding(20.dp, 24.dp, 20.dp, 12.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth().padding(16.dp, 0.dp).focusRequester(focus),
            placeholder = { Text("Type here to test suggestions, emoji and gestures") },
            minLines = 4,
        )
    }
}
