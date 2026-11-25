package org.nacre.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.CallLog.Calls
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

private val SECRET_CODE = Regex("""\*#\*#(\d+)#\*#\*""")

private val TAB_ICONS =
    listOf(
        R.drawable.ic_star,
        R.drawable.ic_history,
        R.drawable.ic_person,
        R.drawable.ic_dialpad,
    )

@Composable
fun HomeScreen(
    tab: Tab,
    navigation: Navigation,
    onTab: (Tab) -> Unit,
    onHistory: (Entry) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.zip(TAB_ICONS).forEach { (entry, icon) ->
                    NavigationBarItem(
                        selected = entry == tab,
                        onClick = { onTab(entry) },
                        icon = { Icon(painterResource(icon), null) },
                        label = { Text(entry.name) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (tab != Tab.Keypad) Header(tab.name, onSettings)
            when (tab) {
                Tab.Favorites -> Favorites()
                Tab.Recents -> Recents(onHistory)
                Tab.Contacts -> ContactsTab()
                Tab.Keypad -> Keypad(navigation)
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        IconButton(onClick = onSettings) {
            Icon(painterResource(R.drawable.ic_settings), "Settings")
        }
    }
}

@Composable
private fun rememberContactCaller(): (Contact) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<Number>()) }
    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choices = emptyList() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choices = emptyList() }) { Text("Cancel") }
            },
            title = { Text(choices.first().contact.name) },
            text = {
                Column {
                    choices.forEach { choice ->
                        ListItem(
                            supportingContent = { Text(choice.label) },
                            colors =
                                ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                ),
                            modifier =
                                Modifier.clickable {
                                    choices = emptyList()
                                    context.call(choice.number)
                                },
                        ) { Text(context.formatNumber(choice.number)) }
                    }
                }
            },
        )
    }
    return { contact ->
        scope.launch {
            val numbers =
                withContext(Dispatchers.IO) {
                    runCatching { context.numbers(contact.id) }.getOrDefault(emptyList())
                }
            if (numbers.size ==
                1
            ) {
                context.call(numbers.first().number)
            } else {
                choices = numbers
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Favorites() {
    val context = LocalContext.current
    val callContact = rememberContactCaller()
    val favorites by rememberQuery<List<Contact>?>(null) {
        contentResolver.contacts(starred = true)
    }
    val list = favorites ?: return
    if (list.isEmpty()) {
        Empty("No favorites yet.\nStar contacts in Contacts to call them quickly here.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(list, key = { it.id }) { contact ->
            Column(
                Modifier
                    .combinedClickable(
                        onClick = { callContact(contact) },
                        onLongClick = { context.viewContact(contact.uri) },
                    ).padding(8.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterHorizontally,
            ) {
                Avatar(contact.name, contact.photo, 72.dp)
                Text(
                    contact.name,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Recents(onHistory: (Entry) -> Unit) {
    val context = LocalContext.current
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    val groups by rememberQuery<List<List<Entry>>?>(null, missedOnly) {
        callLog().filter { !missedOnly || it.type == Calls.MISSED_TYPE }.grouped()
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { context.contentResolver.markMissedRead() }
        runCatching {
            context
                .getSystemService(
                    TelecomManager::class.java,
                ).cancelMissedCallsNotification()
        }
    }
    Row(Modifier.padding(horizontal = 24.dp), Arrangement.spacedBy(8.dp)) {
        FilterChip(!missedOnly, { missedOnly = false }, { Text("All") })
        FilterChip(missedOnly, { missedOnly = true }, { Text("Missed") })
    }
    val list = groups ?: return
    if (list.isEmpty()) {
        Empty(if (missedOnly) "No missed calls" else "No recent calls")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(list, key = { it.first().id }) { group -> RecentRow(group, onHistory) }
    }
}

@Composable
private fun RecentRow(
    group: List<Entry>,
    onHistory: (Entry) -> Unit,
) {
    val context = LocalContext.current
    val entry = group.first()
    val missed = entry.type == Calls.MISSED_TYPE
    val count = if (group.size > 1) " (${group.size})" else ""
    val time =
        DateUtils.formatSameDayTime(
            entry.date,
            System.currentTimeMillis(),
            DateFormat.SHORT,
            DateFormat.SHORT,
        )
    val detail =
        listOf(typeLabel(entry.type), entry.detail, time.toString())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    ListItem(
        leadingContent = { Avatar(entry.name, entry.photo, 40.dp) },
        supportingContent = {
            Text(
                detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (entry.dialable) {
                IconButton(onClick = { context.call(entry.number) }) {
                    Icon(painterResource(R.drawable.ic_call), "Call")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onHistory(entry) }.padding(horizontal = 8.dp),
    ) {
        Text(
            context.displayName(entry) + count,
            fontWeight = if (missed) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

val Entry.dialable get() =
    number.isNotEmpty() &&
        presentation == Calls.PRESENTATION_ALLOWED

fun Context.displayName(entry: Entry) =
    when {
        entry.presentation == Calls.PRESENTATION_RESTRICTED -> "Private number"
        entry.presentation == Calls.PRESENTATION_PAYPHONE -> "Payphone"
        entry.name.isNotEmpty() -> entry.name
        entry.number.isNotEmpty() -> formatNumber(entry.number)
        else -> "Unknown"
    }

fun typeLabel(type: Int) =
    when (type) {
        Calls.INCOMING_TYPE -> "Incoming"
        Calls.OUTGOING_TYPE -> "Outgoing"
        Calls.MISSED_TYPE -> "Missed"
        Calls.VOICEMAIL_TYPE -> "Voicemail"
        Calls.REJECTED_TYPE -> "Declined"
        Calls.BLOCKED_TYPE -> "Blocked"
        Calls.ANSWERED_EXTERNALLY_TYPE -> "Answered elsewhere"
        else -> "Call"
    }

@Composable
private fun ContactsTab() {
    val context = LocalContext.current
    val callContact = rememberContactCaller()
    var query by rememberSaveable { mutableStateOf("") }
    val contacts by rememberQuery<List<Contact>?>(null, query) {
        contentResolver.contacts(query)
    }
    SearchField(query) { query = it }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ListItem(
                leadingContent = {
                    Box(Modifier.size(40.dp), Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_person_add), null)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier
                        .clickable { context.createContact("") }
                        .padding(horizontal = 8.dp),
            ) { Text("Create new contact") }
        }
        val list = contacts ?: return@LazyColumn
        if (list.isEmpty()) {
            item {
                Empty(
                    if (query.isBlank()) "No contacts with numbers" else "No matches",
                )
            }
        }
        items(list, key = { it.id }) { contact ->
            ListItem(
                leadingContent = { Avatar(contact.name, contact.photo, 40.dp) },
                trailingContent = {
                    IconButton(onClick = { callContact(contact) }) {
                        Icon(painterResource(R.drawable.ic_call), "Call ${contact.name}")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier
                        .clickable { context.viewContact(contact.uri) }
                        .padding(horizontal = 8.dp),
            ) { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        placeholder = { Text("Search contacts") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), "Clear search")
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(navigation: Navigation) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tones = rememberTones()
    val digits = navigation.digits
    val matches by rememberQuery(emptyList<Number>(), digits) { matches(digits) }
    val type: (String) -> Unit = { navigation.digits = it.take(MAX_DIGITS) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (digits.isEmpty()) return@LazyColumn
            items(matches, key = { "${it.contact.id}-${it.number}" }) { match ->
                ListItem(
                    leadingContent = {
                        Avatar(
                            match.contact.name,
                            match.contact.photo,
                            40.dp,
                        )
                    },
                    supportingContent = {
                        Text("${match.label} · ${context.formatNumber(match.number)}")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable { context.call(match.number) }
                            .padding(horizontal = 8.dp),
                ) { Text(match.contact.name, maxLines = 1) }
            }
            item {
                Action("Create new contact") { context.createContact(digits) }
                Action("Add to a contact") { context.addToContact(digits) }
                Action("Send a message") { context.message(digits) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                context.formatNumber(digits),
                Modifier.weight(1f).padding(start = 48.dp).combinedClickable(
                    onClick = {},
                    onLongClick = { context.paste()?.let { type(digits + it) } },
                ),
                fontSize = 32.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            Box(
                Modifier.size(48.dp).combinedClickable(
                    enabled = digits.isNotEmpty(),
                    onClick = { type(digits.dropLast(1)) },
                    onLongClick = { type("") },
                ),
                Alignment.Center,
            ) {
                if (digits.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_backspace), "Delete")
                }
            }
        }
        Dialpad(
            onPress = { key ->
                tones.start(key)
                val next = digits + key
                type(if (context.sendSecretCode(next)) "" else next)
            },
            onRelease = tones::stop,
            onLongPress = { key ->
                when {
                    key == '0' -> {
                        type(navigation.digits.dropLast(1) + "+")
                    }

                    key == '1' && navigation.digits == "1" -> {
                        type("")
                        context.callVoicemail()
                    }
                }
            },
        )
        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
            RoundButton(R.drawable.ic_call, "Call") {
                if (digits.isNotEmpty()) {
                    context.call(digits)
                    type("")
                    return@RoundButton
                }
                scope.launch {
                    val last =
                        withContext(Dispatchers.IO) {
                            runCatching { Calls.getLastOutgoingCall(context) }.getOrNull()
                        }
                    type(last.orEmpty())
                }
            }
        }
    }
}

private fun Context.paste(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java)
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this) ?: return null
    return text.filter { it.isDigit() || it in "+*#,;" }.toString().ifEmpty { null }
}

private fun Context.sendSecretCode(digits: String): Boolean {
    val code = SECRET_CODE.matchEntire(digits)?.groupValues?.get(1) ?: return false
    return runCatching {
        getSystemService(TelephonyManager::class.java).sendDialerSpecialCode(code)
    }.isSuccess
}

@Composable
fun HistoryScreen(
    number: String,
    presentation: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by rememberQuery<List<Entry>?>(null, number, presentation) {
        callLog().filter {
            it.presentation == presentation &&
                PhoneNumberUtils.normalizeNumber(it.number) ==
                PhoneNumberUtils.normalizeNumber(number)
        }
    }
    val known by rememberQuery<Number?>(null, number) { lookup(number) }
    val blocked by rememberQuery(false, number) { isBlocked(number) }
    val list = entries ?: return
    val latest =
        list.firstOrNull() ?: Entry(0, number, "", null, 0, 0, 0, presentation, "")
    val dialable = latest.dialable
    Page(
        "Call details",
        onBack,
        actions = {
            IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.deleteCalls(
                                list.map { it.id },
                            )
                        }
                    }
                    onBack()
                }
            }) { Icon(painterResource(R.drawable.ic_delete), "Delete history") }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val name = known?.contact?.name ?: context.displayName(latest)
                    Avatar(name, known?.contact?.photo ?: latest.photo, 96.dp)
                    Text(
                        name,
                        Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (dialable && name != context.formatNumber(number)) {
                        val label = known?.label ?: latest.detail
                        Text(
                            listOf(label, context.formatNumber(number))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            if (dialable) {
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp), Arrangement.SpaceEvenly) {
                        Shortcut(R.drawable.ic_call, "Call") { context.call(number) }
                        Shortcut(
                            R.drawable.ic_message,
                            "Message",
                        ) { context.message(number) }
                        val contact = known?.contact
                        if (contact != null) {
                            Shortcut(R.drawable.ic_person, "Contact") {
                                context.viewContact(contact.uri)
                            }
                        } else {
                            Shortcut(R.drawable.ic_person_add, "Add") {
                                context.addToContact(number)
                            }
                        }
                        Shortcut(
                            R.drawable.ic_block,
                            if (blocked) "Unblock" else "Block",
                        ) {
                            scope.launch(
                                Dispatchers.IO,
                            ) { context.setBlocked(number, !blocked) }
                        }
                        Shortcut(R.drawable.ic_copy, "Copy") { context.copy(number) }
                    }
                }
            }
            items(list, key = { it.id }) { entry ->
                val date =
                    DateUtils.formatDateTime(
                        context,
                        entry.date,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                            DateUtils.FORMAT_ABBREV_MONTH,
                    )
                ListItem(
                    supportingContent = { Text(date) },
                    trailingContent = {
                        if (entry.duration > 0) Text(duration(entry.duration))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(typeLabel(entry.type)) }
            }
        }
    }
}

@Composable
private fun Shortcut(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null)
        Text(
            label,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun Context.copy(number: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", number))
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telecom = context.getSystemService(TelecomManager::class.java)
    var clearing by remember { mutableStateOf(false) }
    Page("Settings", onBack) {
        Column {
            Action("Blocked numbers", "Calls and texts you don't want") {
                context.open(telecom.createManageBlockedNumbersIntent())
            }
            Action("Calls", "Forwarding, call waiting and caller ID") {
                context.open(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))
            }
            Action("Calling accounts", "SIM cards and calling apps") {
                context.open(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
            }
            Action("Sounds and vibration", "Ringtone, dialpad tones and vibration") {
                context.open(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
            Action("Clear call history", "Delete every call from Recents") {
                clearing =
                    true
            }
        }
    }
    if (!clearing) return
    AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Clear call history?") },
        text = { Text("All calls are removed from Recents. This can't be undone.") },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                clearing = false
                scope.launch(Dispatchers.IO) {
                    runCatching { context.contentResolver.clearCallLog() }
                }
            }) { Text("Clear") }
        },
    )
}
