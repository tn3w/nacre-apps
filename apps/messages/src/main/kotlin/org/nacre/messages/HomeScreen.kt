package org.nacre.messages

import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    archived: Boolean,
    navigation: Navigation,
    onBack: () -> Unit,
) {
    val pinned by Store.pinned.collectAsState()
    val archive by Store.archived.collectAsState()
    val all by rememberQuery<List<Conversation>?>(null) { conversations() }
    val list =
        all
            ?.filter { (it.id in archive) == archived }
            ?.sortedByDescending { it.id in pinned }
    Scaffold(
        floatingActionButton = {
            if (!archived) {
                ExtendedFloatingActionButton(
                    onClick = { navigation.push(Screen.New) },
                    icon = { Icon(painterResource(R.drawable.ic_edit), null) },
                    text = { Text("Start chat") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (archived) {
                Header("Archived", onBack)
            } else {
                Header("Messages", null) {
                    IconButton(onClick = { navigation.push(Screen.Search) }) {
                        Icon(painterResource(R.drawable.ic_search), "Search")
                    }
                    IconButton(onClick = { navigation.push(Screen.Settings) }) {
                        Icon(painterResource(R.drawable.ic_settings), "Settings")
                    }
                }
            }
            val conversations = list ?: return@Column
            if (conversations.isEmpty()) {
                Empty(
                    if (archived) "No archived conversations" else "No conversations yet",
                )
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation,
                    ) { navigation.push(Screen.Thread(conversation.id)) }
                }
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier.padding(
            start =
                if (onBack ==
                    null
                ) {
                    24.dp
                } else {
                    8.dp
                },
            end = 8.dp,
            top = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
            ) { Icon(painterResource(R.drawable.ic_back), "Back") }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val pinned by Store.pinned.collectAsState()
    val muted by Store.muted.collectAsState()
    val drafts by Store.drafts.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val draft = drafts[conversation.id]
    val first = conversation.people.first()
    val unread = !conversation.read
    Box {
        ListItem(
            leadingContent = {
                Avatar(
                    first.name,
                    first.photo.takeIf { conversation.people.size == 1 },
                    48.dp,
                    group =
                        conversation.people.size > 1,
                )
            },
            headlineContent = {
                Text(
                    conversation.title,
                    fontWeight = if (unread) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    when {
                        conversation.error -> "Not sent"
                        draft != null -> "Draft: $draft"
                        else -> conversation.snippet.ifEmpty { "Multimedia message" }
                    },
                    color =
                        if (unread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    fontWeight = if (unread) FontWeight.Bold else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        shortTime(context, conversation.date),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (unread) FontWeight.Bold else null,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (conversation.id in muted) Small(R.drawable.ic_mute, "Muted")
                        if (conversation.id in pinned) Small(R.drawable.ic_pin, "Pinned")
                        if (unread) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .padding(1.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier
                    .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                    .padding(horizontal = 8.dp),
        )
        ConversationMenu(conversation, menu) { menu = false }
    }
}

@Composable
private fun Small(
    icon: Int,
    description: String,
) = Icon(
    painterResource(icon),
    description,
    Modifier.size(16.dp),
    MaterialTheme.colorScheme.outline,
)

@Composable
fun ConversationMenu(
    conversation: Conversation,
    expanded: Boolean,
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pinned by Store.pinned.collectAsState()
    val archived by Store.archived.collectAsState()
    val muted by Store.muted.collectAsState()
    var deleting by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    val id = conversation.id
    DropdownMenu(expanded, onDismiss) {
        val item = @Composable { label: String, action: () -> Unit ->
            DropdownMenuItem(text = { Text(label) }, onClick = {
                onDismiss()
                action()
            })
        }
        item(if (id in pinned) "Unpin" else "Pin") { Store.setPinned(id, id !in pinned) }
        item(
            if (id in
                archived
            ) {
                "Unarchive"
            } else {
                "Archive"
            },
        ) { Store.setArchived(id, id !in archived) }
        item(if (id in muted) "Unmute" else "Mute") { Store.setMuted(id, id !in muted) }
        item(if (conversation.read) "Mark as unread" else "Mark as read") {
            scope.launch(Dispatchers.IO) {
                if (conversation.read) {
                    runCatching { context.contentResolver.markUnread(id) }
                } else {
                    context.markThreadRead(id)
                }
            }
        }
        if (conversation.people.size == 1) item("Block") { blocking = true }
        item("Delete") { deleting = true }
    }
    if (deleting) {
        Confirm(
            "Delete this conversation?",
            "All messages in it are removed from this device.",
            "Delete",
            onDismiss = { deleting = false },
        ) {
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.deleteThreads(listOf(id)) }
                Store.forget(setOf(id))
            }
            onDeleted()
        }
    }
    if (blocking) {
        val address = conversation.addresses.first()
        Confirm(
            "Block ${context.displayAddress(address)}?",
            "You won't get calls or texts from this number. " +
                "The conversation is archived.",
            "Block",
            onDismiss = { blocking = false },
        ) {
            scope.launch(Dispatchers.IO) {
                if (context.setBlocked(address, true)) Store.setArchived(id, true)
            }
        }
    }
}

@Composable
fun Confirm(
    title: String,
    text: String,
    action: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(action) }
        },
    )
}

@Composable
fun SearchScreen(
    navigation: Navigation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val conversations by rememberQuery(emptyList<Conversation>()) { conversations() }
    val messages by rememberQuery(emptyList<Message>(), query) { search(query) }
    val matching =
        conversations.filter { conversation ->
            query.isNotBlank() &&
                conversation.people.any {
                    it.name.contains(query.trim(), ignoreCase = true) ||
                        it.address.filter(Char::isDigit).let { digits ->
                            digits.isNotEmpty() &&
                                query.filter(Char::isDigit).let {
                                    it.length >= 3 &&
                                        it in digits
                                }
                        }
                }
        }
    val titles = conversations.associate { it.id to it.title }
    Page("Search", onBack) {
        SearchField(query, "Search messages and people") { query = it }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(matching, key = { "c${it.id}" }) { conversation ->
                val first = conversation.people.first()
                Row1(
                    conversation.title,
                    first.address.let(context::displayAddress),
                    first,
                ) {
                    navigation.push(Screen.Thread(conversation.id))
                }
            }
            items(messages, key = { it.key }) { message ->
                Row1(
                    titles[message.thread] ?: context.displayAddress(message.address),
                    message.body,
                    null,
                ) {
                    navigation.push(Screen.Thread(message.thread))
                }
            }
            if (query.isNotBlank() && matching.isEmpty() &&
                messages.isEmpty()
            ) {
                item { Empty("No results") }
            }
        }
    }
}

@Composable
private fun Row1(
    title: String,
    detail: String,
    person: Person?,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Avatar(person?.name ?: title, person?.photo, 40.dp) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                detail,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
fun NewScreen(
    navigation: Navigation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val contacts by rememberQuery(emptyList<Contact>(), query) { contacts(query) }
    val typed = query.trim().takeIf { it.isPhoneNumber() && it.count(Char::isDigit) >= 3 }
    val open: (List<String>) -> Unit = { addresses ->
        scope.launch {
            val thread =
                withContext(Dispatchers.IO) {
                    runCatching { context.threadFor(addresses) }.getOrNull()
                }
            if (thread != null) navigation.replace(Screen.Thread(thread))
        }
    }
    val pick: (String) -> Unit = { number ->
        if (!group) {
            open(listOf(number))
        } else if (chosen.none { sameAddress(it, number) } &&
            chosen.size < MAX_RECIPIENTS
        ) {
            chosen += number
            query = ""
        }
    }
    Page(
        "New conversation",
        onBack,
        actions = {
            if (chosen.isNotEmpty()) {
                TextButton(
                    onClick = { open(chosen.toList()) },
                ) { Text("Next") }
            }
        },
    ) {
        if (chosen.isNotEmpty()) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                Arrangement.spacedBy(8.dp),
            ) {
                chosen.forEach { number ->
                    FilterChip(
                        true,
                        { chosen.remove(number) },
                        { Text(context.displayAddress(number)) },
                    )
                }
            }
        }
        SearchField(query, "Type a name or number") { query = it }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (typed != null) {
                item {
                    Action(
                        "Send to ${context.displayAddress(typed)}",
                    ) { pick(typed) }
                }
            }
            if (!group && query.isEmpty()) {
                item { Action("Create group") { group = true } }
            }
            items(contacts, key = { "${it.name}-${it.number}" }) { contact ->
                ListItem(
                    leadingContent = { Avatar(contact.name, contact.photo, 40.dp) },
                    headlineContent = {
                        Text(
                            contact.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            "${contact.label} · ${context.displayAddress(
                                contact.number,
                            )}",
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable {
                                pick(
                                    contact.number,
                                )
                            }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    navigation: Navigation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val reports by Store.deliveryReports.collectAsState()
    val unknown by Store.notifyUnknown.collectAsState()
    val download by Store.autoDownload.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val default =
        context
            .getSystemService(
                RoleManager::class.java,
            ).isRoleHeld(RoleManager.ROLE_SMS)
    Page("Settings", onBack) {
        Column {
            if (!default) {
                Action(
                    "Set as default SMS app",
                    "Messages can't send or receive texts until then",
                ) {
                    val roles = context.getSystemService(RoleManager::class.java)
                    context.open(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
            }
            Action(
                "Archived conversations",
            ) { navigation.push(Screen.Home(archived = true)) }
            Action("Notifications", "Sound, vibration and lock screen") {
                context.open(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
            Action(
                "Notify for unknown senders",
                "Alert for texts from numbers that aren't in your contacts",
                trailing = { Switch(unknown, null) },
            ) { Store.setNotifyUnknown(!unknown) }
            Action("Auto-download MMS", download.label) { choosing = true }
            Action(
                "SMS delivery reports",
                "Ask the network to confirm each text was delivered",
                trailing = { Switch(reports, null) },
            ) { Store.setDeliveryReports(!reports) }
            Action("Blocked numbers", "Calls and texts you don't want") {
                val telecom = context.getSystemService(TelecomManager::class.java)
                context.open(telecom.createManageBlockedNumbersIntent())
            }
        }
    }
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Auto-download MMS") },
        text = {
            Column {
                AutoDownload.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            Store.setAutoDownload(option)
                            choosing = false
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(option == download, null)
                        Text(
                            option.label,
                            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
                Text(
                    "Pictures and group texts from others are fetched only " +
                        "when you tap Download.",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
}
