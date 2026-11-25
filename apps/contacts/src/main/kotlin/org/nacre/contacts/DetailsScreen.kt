package org.nacre.contacts

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.Contacts
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val HEADER_KINDS = setOf(Kind.Name, Kind.Organization)

@Composable
fun DetailsScreen(contactId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val details by rememberQuery<Details?>(null, contactId) { details(contactId) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val current = details

    Page("", onBack, {
        current ?: return@Page
        IconButton(onClick = {
            context.contentResolver.setStarred(contactId, !current.contact.starred)
        }) {
            Icon(
                painterResource(R.drawable.ic_star),
                if (current.contact.starred) "Remove from favorites" else "Add to favorites",
                tint = if (current.contact.starred) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            )
        }
        IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), "Edit") }
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.ic_more), "More")
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Share") }, {
                    menu = false
                    context.share(current.contact)
                })
                DropdownMenuItem({ Text("Delete") }, {
                    menu = false
                    deleting = true
                })
            }
        }
    }) {
        current?.let { DetailsContent(it) }
    }

    if (deleting && current != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete contact?") },
            text = { Text("${current.contact.name} will be removed from this device.") },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    context.contentResolver.deleteContact(current.contact)
                    onBack()
                }) { Text("Delete") }
            }
        )
    }
}

@Composable
private fun DetailsContent(details: Details) {
    val context = LocalContext.current
    val organization = details.rows.find { it.kind == Kind.Organization }
    val phone = details.rows.find { it.kind == Kind.Phone }?.value
    val email = details.rows.find { it.kind == Kind.Email }?.value
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterHorizontally
            ) {
                Avatar(details.contact.name, details.photo ?: details.contact.photo, 128.dp)
                Text(
                    details.contact.name.ifBlank { "No name" },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                organization?.let { row ->
                    Text(
                        listOf(row.extra, row.value).filter { it.isNotBlank() }.joinToString(", "),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(Modifier.padding(top = 16.dp), Arrangement.spacedBy(32.dp)) {
                    QuickAction(R.drawable.ic_call, "Call", phone) { context.dial(it) }
                    QuickAction(R.drawable.ic_message, "Text", phone) {
                        context.open(Intent.ACTION_SENDTO, "smsto:${Uri.encode(it)}")
                    }
                    QuickAction(R.drawable.ic_email, "Email", email) {
                        context.open(Intent.ACTION_SENDTO, "mailto:${Uri.encode(it)}")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        }
        items(details.rows.filter { it.kind !in HEADER_KINDS }) { DetailRow(it) }
    }
}

@Composable
private fun QuickAction(icon: Int, label: String, value: String?, onClick: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = { value?.let(onClick) }, enabled = value != null) {
            Icon(painterResource(icon), null)
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(row: Row) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val icon = when (row.kind) {
        Kind.Phone -> R.drawable.ic_call
        Kind.Email -> R.drawable.ic_email
        Kind.Address -> R.drawable.ic_place
        Kind.Website -> R.drawable.ic_web
        Kind.Birthday -> R.drawable.ic_event
        Kind.Nickname -> R.drawable.ic_person
        else -> R.drawable.ic_note
    }
    val label = row.kind.label(context.resources, row.type, row.label)
    ListItem(
        supportingContent = { Text(label) },
        leadingContent = { Icon(painterResource(icon), null) },
        trailingContent = if (row.kind == Kind.Phone) {
            {
                IconButton(onClick = {
                    context.open(Intent.ACTION_SENDTO, "smsto:${Uri.encode(row.value)}")
                }) { Icon(painterResource(R.drawable.ic_message), "Text") }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(
            onLongClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, row.value)))
                }
            },
            onClick = { context.act(row) }
        ).padding(horizontal = 8.dp)
    ) {
        Text(
            when (row.kind) {
                Kind.Birthday -> formatBirthday(row.value)
                Kind.Phone -> context.formatPhone(row.value)
                else -> row.value
            }
        )
    }
}

private fun Context.act(row: Row) = when (row.kind) {
    Kind.Phone -> dial(row.value)
    Kind.Email -> open(Intent.ACTION_SENDTO, "mailto:${Uri.encode(row.value)}")
    Kind.Address -> open(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(row.value)}")
    Kind.Website -> open(
        Intent.ACTION_VIEW,
        if ("://" in row.value) row.value else "https://${row.value}"
    )
    else -> Unit
}

private fun Context.dial(number: String) = open(Intent.ACTION_DIAL, "tel:${Uri.encode(number)}")

private fun Context.open(action: String, uri: String) {
    runCatching { startActivity(Intent(action, Uri.parse(uri))) }
        .onFailure { Toast.makeText(this, "No app can open this", Toast.LENGTH_SHORT).show() }
}

private fun Context.share(contact: Contact) {
    val uri = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI, contact.lookup)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(Contacts.CONTENT_VCARD_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, contact.name))
}

fun formatBirthday(value: String): String = runCatching {
    if (value.startsWith("--")) {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMd")
        MonthDay.parse(value).format(DateTimeFormatter.ofPattern(pattern))
    } else {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMMMd")
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern(pattern))
    }
}.getOrDefault(value)
