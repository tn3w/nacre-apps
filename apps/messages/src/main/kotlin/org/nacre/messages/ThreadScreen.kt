package org.nacre.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.text.DateFormat

private const val IMAGE_SIDE = 1080
private const val VIEWER_SIDE = 2048
private const val MAX_ATTACHMENTS = 10
private const val TEXT_LIMIT = 4000
private val LINK =
    Regex("""\b(?:https?://|www\.)[^\s<>"]{2,2000}""", RegexOption.IGNORE_CASE)

@Composable
fun ThreadScreen(
    thread: Long,
    navigation: Navigation,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val conversation by rememberQuery<Conversation?>(null, thread) {
        conversation(thread)
            ?: emptyConversation(thread)
    }
    val messages by rememberQuery<List<Message>?>(null, thread) { messages(thread) }
    DisposableEffect(thread) {
        Visible.thread = thread
        onDispose { if (Visible.thread == thread) Visible.thread = null }
    }
    LaunchedEffect(messages) {
        if (messages?.any { it.incoming && !it.read } == true) {
            withContext(Dispatchers.IO) { context.markThreadRead(thread) }
        }
    }
    val current = conversation ?: return
    var menu by remember { mutableStateOf(false) }
    Page(
        current.title,
        onBack,
        actions = {
            val single = current.people.singleOrNull()
            if (single != null && single.address.isPhoneNumber()) {
                IconButton(onClick = {
                    context.open(
                        Intent(
                            Intent.ACTION_DIAL,
                            Uri.fromParts("tel", single.address, null),
                        ),
                    )
                }) { Icon(painterResource(R.drawable.ic_call), "Call") }
            }
            if (single != null) {
                IconButton(onClick = { context.showContact(single) }) {
                    Icon(
                        painterResource(
                            if (single.known) R.drawable.ic_person else R.drawable.ic_person_add,
                        ),
                        if (single.known) "View contact" else "Add contact",
                    )
                }
            }
            Box {
                IconButton(onClick = {
                    menu = true
                }) { Icon(painterResource(R.drawable.ic_more), "More") }
                ConversationMenu(current, menu, onDeleted = onBack) { menu = false }
            }
        },
    ) {
        Column(Modifier.imePadding()) {
            val list = messages
            Box(Modifier.weight(1f)) {
                if (list != null &&
                    list.isEmpty()
                ) {
                    Empty("Send a message to start the conversation")
                }
                if (list != null) MessageList(list, current, navigation)
            }
            Composer(thread, current, list.orEmpty(), navigation)
        }
    }
}

private fun Context.emptyConversation(thread: Long): Conversation? {
    val addresses =
        runCatching {
            contentResolver
                .query(
                    android.provider.Telephony.Threads.CONTENT_URI
                        .buildUpon()
                        .appendQueryParameter(
                            "simple",
                            "true",
                        ).build(),
                    arrayOf(android.provider.Telephony.Threads.RECIPIENT_IDS),
                    "${android.provider.Telephony.Threads._ID} = ?",
                    arrayOf(thread.toString()),
                    null,
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return null
    val canonical =
        contentResolver
            .query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(
                            cursor.getLong(0).toString(),
                            cursor.getString(1).orEmpty(),
                        )
                    }
                }
            }.orEmpty()
    val people = People(this)
    val list = addresses.split(' ').mapNotNull(canonical::get).map(people::get)
    if (list.isEmpty()) return null
    return Conversation(thread, list, "", 0, true, false)
}

private fun Context.showContact(person: Person) {
    val contact = person.contact
    if (contact != null) return open(Intent(Intent.ACTION_VIEW, contact))
    val intent =
        Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(
                if (person.address.isPhoneNumber()) {
                    ContactsContract.Intents.Insert.PHONE
                } else {
                    ContactsContract.Intents.Insert.EMAIL
                },
                person.address,
            )
    open(intent)
}

@Composable
private fun MessageList(
    list: List<Message>,
    conversation: Conversation,
    navigation: Navigation,
) {
    val context = LocalContext.current
    val people = remember(conversation) { People(context) }
    val reversed = remember(list) { list.withReactions().asReversed() }
    LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
        reversed.forEachIndexed { index, (message, reactions) ->
            val older = reversed.getOrNull(index + 1)?.message
            val newer = reversed.getOrNull(index - 1)?.message
            item(key = message.key) {
                val sender =
                    if (message.incoming && conversation.people.size > 1 &&
                        older?.address != message.address
                    ) {
                        people[message.address].name
                    } else {
                        null
                    }
                val last = newer == null || newer.incoming != message.incoming
                Bubble(message, reactions, sender, last, conversation, navigation)
            }
            if (older == null || !sameDay(older.date, message.date)) {
                item(key = "day-${message.key}") {
                    Text(
                        DateUtils.formatDateTime(
                            context,
                            message.date,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                                DateUtils.FORMAT_ABBREV_ALL,
                        ),
                        Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun sameDay(
    first: Long,
    second: Long,
): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    val a =
        java.time.Instant
            .ofEpochMilli(first)
            .atZone(zone)
            .toLocalDate()
    val b =
        java.time.Instant
            .ofEpochMilli(second)
            .atZone(zone)
            .toLocalDate()
    return a == b
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: Message,
    reactions: List<String>,
    sender: String?,
    last: Boolean,
    conversation: Conversation,
    navigation: Navigation,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    val outgoing = !message.incoming
    val failed = message.box == Box.Failed
    val colors = MaterialTheme.colorScheme
    val container = if (outgoing) colors.primary else colors.surfaceVariant
    val content = if (outgoing) colors.onPrimary else colors.onSurface
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        if (sender != null) {
            Text(
                sender,
                Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.outline,
            )
        }
        Box {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(container)
                    .combinedClickable(
                        onClick = {
                            if (failed) {
                                scope.launch(
                                    Dispatchers.IO,
                                ) { context.retry(message) }
                            }
                        },
                        onLongClick = { menu = true },
                    ),
            ) {
                message.attachments.forEach { attachment ->
                    if (attachment.isImage) {
                        Thumbnail(
                            attachment.uri,
                        ) { navigation.push(Screen.Viewer(attachment.uri)) }
                    } else {
                        SaveChip(attachment)
                    }
                }
                message.pending?.let { Pending(message, it) }
                if (message.body.isNotEmpty()) {
                    Text(
                        linkified(message.body, content) { link = it },
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = content,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            MessageMenu(
                message,
                conversation,
                menu,
                { menu = false },
                { details = true },
                navigation,
            )
        }
        if (reactions.isNotEmpty()) {
            Text(
                reactions.groupingBy { it }.eachCount().entries.joinToString(
                    " ",
                ) { (emoji, count) ->
                    if (count > 1) "$emoji $count" else emoji
                },
                Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (last || failed || message.box == Box.Outbox) {
            Text(
                status(message),
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.error else colors.outline,
            )
        }
    }
    link?.let { url -> LinkDialog(url) { link = null } }
    if (details) DetailsDialog(message) { details = false }
}

private fun status(message: Message): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(message.date)
    return when (message.box) {
        Box.Outbox -> "Sending…"
        Box.Failed -> "Not sent · Tap to retry"
        Box.Sent -> if (message.delivered) "$time · Delivered" else "$time · Sent"
        Box.Inbox -> time
    }
}

private fun linkified(
    text: String,
    color: Color,
    onLink: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        var position = 0
        LINK.findAll(text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ')', '!', '?', ';', ':')
            append(text.substring(position, match.range.first))
            val style =
                TextLinkStyles(
                    SpanStyle(color = color, textDecoration = TextDecoration.Underline),
                )
            withLink(LinkAnnotation.Clickable(url, style) { onLink(url) }) { append(url) }
            position = match.range.first + url.length
        }
        append(text.substring(position))
    }

@Composable
private fun LinkDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(url) { safeUri(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open link?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    uri?.host ?: "This link can't be opened",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    url,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    context.copyText(url)
                    onDismiss()
                }) { Text("Copy") }
                if (uri != null) {
                    TextButton(onClick = {
                        onDismiss()
                        context.open(
                            Intent(
                                Intent.ACTION_VIEW,
                                uri,
                            ).addCategory(Intent.CATEGORY_BROWSABLE),
                        )
                    }) { Text("Open") }
                }
            }
        },
    )
}

private fun safeUri(url: String): Uri? {
    val full = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
    val uri = runCatching { Uri.parse(full) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
    val host = uri.host ?: return null
    val ascii =
        runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return null
    return uri
        .buildUpon()
        .encodedAuthority(
            uri.encodedAuthority?.replace(host, ascii),
        ).build()
}

fun Context.copyText(text: String) {
    getSystemService(
        ClipboardManager::class.java,
    ).setPrimaryClip(ClipData.newPlainText("Message", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageMenu(
    message: Message,
    conversation: Conversation,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    navigation: Navigation,
) {
    if (!expanded) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reactable =
        message.body.isNotEmpty() && message.box != Box.Failed && message.pending == null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            if (message.body.isNotEmpty()) {
                Text(
                    message.body,
                    Modifier
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reactable) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    Arrangement.SpaceEvenly,
                ) {
                    REACTIONS.forEach { emoji ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    onDismiss()
                                    val text = reactionText(emoji, message.body)
                                    scope.launch(Dispatchers.IO) {
                                        context.send(
                                            conversation.addresses,
                                            text,
                                            emptyList(),
                                            message.subscription,
                                        )
                                    }
                                },
                            Alignment.Center,
                        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
            val item = @Composable { icon: Int, label: String, action: () -> Unit ->
                ListItem(
                    leadingContent = { Icon(painterResource(icon), null) },
                    headlineContent = { Text(label) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clickable {
                                onDismiss()
                                action()
                            }.padding(horizontal = 8.dp),
                )
            }
            if (message.box == Box.Failed) {
                item(R.drawable.ic_send, "Retry") {
                    scope.launch(Dispatchers.IO) { context.retry(message) }
                }
            }
            if (message.body.isNotEmpty()) {
                findCode(message.body)?.let { code ->
                    item(
                        R.drawable.ic_key,
                        "Copy code $code",
                    ) { context.copySensitive(code) }
                }
                item(R.drawable.ic_copy, "Copy text") { context.copyText(message.body) }
                item(R.drawable.ic_forward, "Forward") {
                    navigation.sharedText = message.body
                    navigation.sharedImages.clear()
                    navigation.push(Screen.New)
                }
            }
            item(R.drawable.ic_info, "Details", onDetails)
            item(R.drawable.ic_delete, "Delete") {
                scope.launch(Dispatchers.IO) {
                    runCatching { context.contentResolver.deleteMessage(message) }
                }
            }
        }
    }
}

@Composable
private fun DetailsDialog(
    message: Message,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val date =
        DateUtils.formatDateTime(
            context,
            message.date,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_SHOW_YEAR,
        )
    val lines =
        listOfNotNull(
            "Type" to if (message.mms) "Multimedia message" else "Text message",
            if (message.incoming) {
                "From" to
                    context.displayAddress(
                        message.address,
                    )
            } else {
                null
            },
            (if (message.incoming) "Received" else "Sent") to date,
            "Status" to status(message).substringAfter(" · "),
            message.subscription.takeIf { it >= 0 && context.sims().size > 1 }?.let { id ->
                "SIM" to (context.sims().find { it.id == id }?.label ?: "SIM")
            },
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { (label, value) ->
                    Column {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(value)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Pending(
    message: Message,
    pending: Pending,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val expired = pending.expiry in 1..<(System.currentTimeMillis() / 1000)
    val size = Formatter.formatShortFileSize(context, pending.size)
    val sender = remember(message.address) { People(context)[message.address] }
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Multimedia message · $size", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                pending.state == STATE_DOWNLOADING -> "Downloading…"
                expired -> "This message has expired"
                pending.state == STATE_FAILED -> "Download failed"
                !sender.known -> "From someone not in your contacts"
                else -> "Not downloaded yet"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pending.state != STATE_DOWNLOADING && !expired) {
            AssistChip(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        context.download(
                            message.uri,
                            pending.location,
                            message.subscription,
                        )
                    }
                },
                label = {
                    Text(
                        if (pending.state ==
                            STATE_FAILED
                        ) {
                            "Retry"
                        } else {
                            "Download"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun Thumbnail(
    uri: Uri,
    onClick: () -> Unit,
) {
    val bitmap = rememberDecoded(uri, IMAGE_SIDE)
    Box(
        Modifier
            .widthIn(max = 300.dp)
            .heightIn(min = 120.dp, max = 360.dp)
            .clickable(onClick = onClick),
        Alignment.Center,
    ) {
        val image = bitmap.value
        when {
            image != null -> {
                Image(
                    image,
                    "Photo",
                    Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }

            bitmap.done -> {
                Text(
                    "Image can't be shown",
                    Modifier.padding(16.dp),
                    MaterialTheme.colorScheme.outline,
                )
            }

            else -> {
                Text("Loading…", Modifier.padding(16.dp))
            }
        }
    }
}

private class Decoded(
    val value: ImageBitmap?,
    val done: Boolean,
)

@Composable
private fun rememberDecoded(
    uri: Uri,
    side: Int,
): Decoded {
    val context = LocalContext.current
    val state by produceState(Decoded(null, false), uri, side) {
        val bitmap = withContext(Dispatchers.IO) { Decoder.decode(context, uri, side) }
        value = Decoded(bitmap?.asImageBitmap(), true)
    }
    return state
}

@Composable
private fun SaveChip(attachment: Attachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save =
        rememberLauncherForActivityResult(
            CreateDocument(
                attachment.type.ifEmpty {
                    "application/octet-stream"
                },
            ),
        ) { target ->
            target ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val copied =
                    runCatching {
                        context.contentResolver.openInputStream(attachment.uri)!!.use { input ->
                            context.contentResolver
                                .openOutputStream(
                                    target,
                                )!!
                                .use { input.copyTo(it) }
                        }
                    }.isSuccess
                withContext(
                    Dispatchers.Main,
                ) { context.toast(if (copied) "Saved" else "Couldn't save") }
            }
        }
    AssistChip(
        onClick = { save.launch(attachment.name.ifEmpty { "attachment" }) },
        label = {
            Text(
                "Save ${attachment.name.ifEmpty {
                    attachment.type.ifEmpty { "attachment" }
                }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
fun ViewerScreen(
    uri: Uri,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = rememberDecoded(uri, VIEWER_SIDE)
    val save =
        rememberLauncherForActivityResult(CreateDocument("image/png")) { target ->
            val image = bitmap.value ?: return@rememberLauncherForActivityResult
            target ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                val saved =
                    runCatching {
                        context.contentResolver.openOutputStream(target)!!.use {
                            image.asAndroidBitmap().compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                it,
                            )
                        }
                    }.isSuccess
                withContext(
                    Dispatchers.Main,
                ) { context.toast(if (saved) "Saved" else "Couldn't save") }
            }
        }
    Page(
        "Photo",
        onBack,
        actions = {
            if (bitmap.value != null) {
                IconButton(onClick = { save.launch("photo.png") }) {
                    Icon(painterResource(R.drawable.ic_download), "Save")
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            val image = bitmap.value
            when {
                image != null -> {
                    Image(
                        image,
                        "Photo",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }

                bitmap.done -> {
                    Text("Image can't be shown")
                }
            }
        }
    }
}

@Composable
private fun Composer(
    thread: Long,
    conversation: Conversation,
    messages: List<Message>,
    navigation: Navigation,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts by Store.drafts.collectAsState()
    var text by rememberSaveable(thread) {
        val shared = navigation.sharedText
        navigation.sharedText = ""
        mutableStateOf(shared.ifEmpty { drafts[thread].orEmpty() })
    }
    val images = remember(thread) { mutableStateListOf<Pair<Uri, ImageBitmap>>() }
    val files = remember(thread) { mutableStateListOf<Part>() }
    var attachMenu by remember { mutableStateOf(false) }
    val sims = remember { context.sims() }
    var sim by rememberSaveable(thread) {
        mutableStateOf(
            messages.lastOrNull { it.incoming && it.subscription >= 0 }?.subscription
                ?: defaultSim(),
        )
    }
    var simMenu by remember { mutableStateOf(false) }
    val limit = remember(sim) { context.mmsSizeLimit(sim) }
    val attach: (List<Uri>) -> Unit = { uris ->
        scope.launch {
            uris.take(MAX_ATTACHMENTS - images.size).forEach { uri ->
                val bitmap =
                    withContext(
                        Dispatchers.IO,
                    ) { Decoder.decode(context, uri, IMAGE_SIDE) }
                if (bitmap ==
                    null
                ) {
                    context.toast("This image can't be attached")
                } else {
                    images +=
                        uri to bitmap.asImageBitmap()
                }
            }
        }
    }
    LaunchedEffect(thread) {
        if (navigation.sharedImages.isEmpty()) return@LaunchedEffect
        val shared = navigation.sharedImages.toList()
        navigation.sharedImages.clear()
        attach(shared)
    }
    DisposableEffect(thread) { onDispose { Store.setDraft(thread, text) } }
    val pick =
        rememberLauncherForActivityResult(
            PickMultipleVisualMedia(MAX_ATTACHMENTS),
        ) { attach(it) }
    val pickFile =
        rememberLauncherForActivityResult(OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val part = withContext(Dispatchers.IO) { context.readFile(uri, limit) }
                when {
                    part == null -> {
                        context.toast("This file is too large for a message")
                    }

                    images.size + files.size >= MAX_ATTACHMENTS -> {
                        context.toast(
                            "Too many attachments",
                        )
                    }

                    else -> {
                        files += part
                    }
                }
            }
        }
    val group = conversation.people.size > 1
    val mms = group || images.isNotEmpty() || files.isNotEmpty()
    val length = remember(text) { if (text.isEmpty()) null else smsLength(text) }
    val counter =
        length
            ?.takeIf {
                !mms && (it[0] > 1 || it[2] < 20)
            }?.let { "${it[2]} / ${it[0]}" }
    Surface(tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (images.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp),
                ) {
                    items(images.size) { index ->
                        Box {
                            Image(
                                images[index].second,
                                "Attached photo",
                                Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { images.removeAt(index) },
                                modifier =
                                    Modifier
                                        .align(
                                            Alignment.TopEnd,
                                        ).size(
                                            28.dp,
                                        ).clip(
                                            CircleShape,
                                        ).background(MaterialTheme.colorScheme.surface),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    "Remove",
                                    Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            files.forEachIndexed { index, file ->
                InputChip(
                    selected = false,
                    onClick = { files.removeAt(index) },
                    label = {
                        Text(
                            file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_file),
                            null,
                            Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            "Remove",
                            Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) {
                        Icon(painterResource(R.drawable.ic_attach), "Attach")
                    }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, onClick = {
                            attachMenu = false
                            pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("File") }, onClick = {
                            attachMenu = false
                            pickFile.launch(arrayOf("*/*"))
                        })
                    }
                }
                TextField(
                    value = text,
                    onValueChange = { text = it.take(TEXT_LIMIT) },
                    modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                    placeholder = { Text(if (mms) "MMS message" else "Text message") },
                    shape = RoundedCornerShape(24.dp),
                    supportingText = counter?.let { { Text(it) } },
                    colors =
                        TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                )
                if (sims.size > 1) {
                    Box {
                        TextButton(onClick = { simMenu = true }) {
                            Text(sims.find { it.id == sim }?.label?.take(8) ?: "SIM")
                        }
                        DropdownMenu(simMenu, { simMenu = false }) {
                            sims.forEach {
                                DropdownMenuItem(text = { Text(it.label) }, onClick = {
                                    sim = it.id
                                    simMenu = false
                                })
                            }
                        }
                    }
                }
                FilledIconButton(
                    onClick = {
                        val body = text.trim()
                        val photos = images.map { it.second }
                        val attached = files.toList()
                        if (body.isEmpty() && photos.isEmpty() &&
                            attached.isEmpty()
                        ) {
                            return@FilledIconButton
                        }
                        text = ""
                        images.clear()
                        files.clear()
                        Store.setDraft(thread, "")
                        scope.launch(Dispatchers.IO) {
                            val budget =
                                (limit - attached.sumOf { it.data.size }) /
                                    maxOf(1, photos.size)
                            val parts =
                                photos.mapIndexed {
                                    index,
                                    image,
                                    ->
                                    image.toJpeg(index, budget)
                                } +
                                    attached
                            context.send(conversation.addresses, body, parts, sim)
                        }
                    },
                    enabled =
                        text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                ) { Icon(painterResource(R.drawable.ic_send), "Send") }
            }
        }
    }
}

private fun ImageBitmap.toJpeg(
    index: Int,
    budget: Int,
): Part {
    var bitmap = asAndroidBitmap()
    var quality = 85
    while (true) {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        if (bytes.size <= budget * 9 / 10 ||
            bitmap.width < 160
        ) {
            return Part("image/jpeg", "image$index.jpg", bytes)
        }
        if (quality > 50) {
            quality -= 15
        } else {
            bitmap =
                Bitmap.createScaledBitmap(
                    bitmap,
                    bitmap.width * 3 / 4,
                    bitmap.height * 3 / 4,
                    true,
                )
        }
    }
}

private fun Context.readFile(
    uri: Uri,
    limit: Int,
): Part? {
    if (uri.scheme != "content" ||
        uri.authority in setOf("org.nacre.messages.files", "mms", "sms")
    ) {
        return null
    }
    val name =
        contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
            .clean()
            .replace(Regex("[/\\\\]"), "_")
            .ifEmpty { "file" }
    val type =
        contentResolver.getType(uri)?.lowercase()?.takeIf { '/' in it }
            ?: "application/octet-stream"
    val bytes =
        contentResolver.openInputStream(uri)?.use { it.readNBytes(limit + 1) }
            ?: return null
    if (bytes.size > limit * 9 / 10) return null
    return Part(type, name, bytes)
}
