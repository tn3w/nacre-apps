package org.nacre.contacts

import android.content.Context
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VCARD_TYPES = arrayOf("text/vcard", "text/x-vcard", "text/directory")

@Composable
fun HomeScreen(
    tab: Tab,
    navigation: Navigation,
    onTab: (Tab) -> Unit,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            if (tab == Tab.Contacts) {
                FloatingActionButton(onClick = onCreate) {
                    Icon(painterResource(R.drawable.ic_add), "Create contact")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                val icons =
                    listOf(R.drawable.ic_person, R.drawable.ic_star, R.drawable.ic_tune)
                Tab.entries.zip(icons).forEach { (entry, icon) ->
                    NavigationBarItem(
                        selected = entry == tab,
                        onClick = { onTab(entry) },
                        icon = { Icon(painterResource(icon), null) },
                        label = { Text(entry.name) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Contacts -> ContactList(onOpen)
                Tab.Favorites -> Favorites(onOpen)
                Tab.Manage -> Manage(navigation)
            }
        }
    }
    navigation.importUri?.let { ImportDialog(it) { navigation.importUri = null } }
}

@Composable
private fun ContactList(onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val sortByLast by Store.sortByLast.collectAsState()
    val lastFirst by Store.lastFirst.collectAsState()
    val contacts by rememberQuery<List<Contact>?>(null, query, sortByLast, lastFirst) {
        contacts(query, sortByLast, lastFirst)
    }
    Column(Modifier.statusBarsPadding()) {
        SearchField(query) { query = it }
        val list = contacts ?: return@Column
        if (list.isEmpty()) {
            Empty(if (query.isBlank()) "No contacts yet" else "No matches")
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            contactItems(list, query.isBlank()) { onOpen(it.id) }
        }
    }
}

fun LazyListScope.contactItems(
    contacts: List<Contact>,
    sections: Boolean,
    onClick: (Contact) -> Unit
) {
    val starred = contacts.filter { it.starred }
    if (sections && starred.isNotEmpty()) {
        item { Section("Favorites") }
        items(starred, key = { "starred-${it.id}" }) { ContactRow(it, onClick = onClick) }
    }
    contacts.groupBy { section(it.name) }.forEach { (letter, group) ->
        if (sections) item(key = "section-$letter") { Section(letter) }
        items(group, key = { it.id }) { ContactRow(it, onClick = onClick) }
    }
}

private fun section(name: String) =
    name.firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#"

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelLarge
    )
}

@Composable
fun ContactRow(contact: Contact, detail: String? = null, onClick: (Contact) -> Unit) {
    ListItem(
        supportingContent = detail?.let { { Text(it, maxLines = 1) } },
        leadingContent = { Avatar(contact.name, contact.photo, 40.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick(contact) }.padding(horizontal = 8.dp)
    ) {
        Text(
            contact.name.ifBlank { "No name" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit) {
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
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Favorites(onOpen: (Long) -> Unit) {
    val favorites by rememberQuery<List<Contact>?>(null) {
        contacts().filter { it.starred }
    }
    val list = favorites ?: return
    if (list.isEmpty()) {
        Empty("No favorites yet.\nStar a contact to keep it here.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(list, key = { it.id }) { contact ->
            Column(
                Modifier.clickable { onOpen(contact.id) }.padding(8.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterHorizontally
            ) {
                Avatar(contact.name, contact.photo, 72.dp)
                Text(
                    contact.name,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun Manage(navigation: Navigation) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sortByLast by Store.sortByLast.collectAsState()
    val lastFirst by Store.lastFirst.collectAsState()
    val count by rememberQuery<Int?>(null) { contacts().size }
    val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        navigation.importUri = uri
    }
    val exporter =
        rememberLauncherForActivityResult(CreateDocument("text/x-vcard")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch { context.export(uri) }
        }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Text(
                "Manage",
                Modifier.padding(24.dp, 16.dp),
                style = MaterialTheme.typography.headlineMedium
            )
        }
        item {
            Setting("Sort by last name", sortByLast, Store::setSortByLast)
            Setting("Show last name first", lastFirst, Store::setLastFirst)
            HorizontalDivider(Modifier.padding(16.dp, 8.dp))
            Action("Import from file", "Add contacts from a .vcf file") {
                importer.launch(VCARD_TYPES + "application/octet-stream")
            }
            val exportDetail = count?.let { "Save ${contactCount(it)} as a .vcf file" }
            Action("Export to file", exportDetail) {
                exporter.launch("contacts.vcf")
            }
            Action("Blocked numbers", "Calls and texts you don't want") {
                val telecom = context.getSystemService(TelecomManager::class.java)
                runCatching {
                    context.startActivity(telecom.createManageBlockedNumbersIntent())
                }
            }
        }
    }
}

@Composable
private fun Setting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) }.padding(horizontal = 8.dp)
    ) { Text(title) }
}

@Composable
private fun Action(title: String, detail: String?, onClick: () -> Unit) {
    ListItem(
        supportingContent = detail?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp)
    ) { Text(title) }
}

private suspend fun Context.export(uri: Uri) {
    val count = withContext(Dispatchers.IO) {
        runCatching { contentResolver.exportVcards(uri) }.getOrNull()
    }
    val message = count?.let { "Exported ${contactCount(it)}" } ?: "Export failed"
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ImportDialog(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val count by produceState<Int?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.countVcards(uri) }.getOrDefault(0)
        }
    }
    val found = count ?: return
    if (found == 0) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            title = { Text("Nothing to import") },
            text = { Text("The file contains no readable contacts.") }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import contacts?") },
        text = { Text("Add ${contactCount(found)} from this file to your device.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                scope.launch { context.import(uri) }
            }) { Text("Import") }
        }
    )
}

private suspend fun Context.import(uri: Uri) {
    val count = withContext(Dispatchers.IO) {
        runCatching { contentResolver.importVcards(uri) }.getOrNull()
    }
    val message = count?.let { "Imported ${contactCount(it)}" } ?: "Import failed"
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@Composable
fun PickScreen(
    kind: Kind?,
    creating: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onPick: (Uri, Long) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val title = when (kind) {
        Kind.Phone -> "Choose a phone number"
        Kind.Email -> "Choose an email address"
        Kind.Address -> "Choose an address"
        else -> "Choose a contact"
    }
    val contacts by rememberQuery<List<Contact>>(emptyList(), query, kind) {
        if (kind == null) contacts(query) else emptyList()
    }
    val pickables by rememberQuery<List<Pickable>>(emptyList(), query, kind) {
        kind?.let { pickables(it, query) }.orEmpty()
    }
    Page(title, onBack) {
        SearchField(query) { query = it }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (creating) item { Action("Create new contact", null, onCreate) }
            contactItems(contacts, sections = false) { onPick(it.uri, it.id) }
            items(pickables, key = { it.uri.toString() }) { pickable ->
                ContactRow(pickable.contact, pickable.detail) {
                    onPick(pickable.uri, it.id)
                }
            }
        }
    }
}

private fun contactCount(count: Int) = if (count == 1) "1 contact" else "$count contacts"
