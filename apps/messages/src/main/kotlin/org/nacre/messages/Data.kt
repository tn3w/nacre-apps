package org.nacre.messages

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.Locale

private const val NAME_LIMIT = 100
private const val BODY_LIMIT = 20_000
private const val MARKS_LIMIT = 3
private const val THREAD_LIMIT = 1000
private const val SEARCH_LIMIT = 200
private val CANONICAL_ADDRESSES = Uri.parse("content://mms-sms/canonical-addresses")
private val HIDDEN =
    listOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
    ).map(Byte::toInt)
private val BIDI_OVERRIDES = ('‪'..'‮') + ('⁦'..'⁩')
private val MARKS =
    listOf(
        Character.NON_SPACING_MARK,
        Character.ENCLOSING_MARK,
    ).map(Byte::toInt)

fun CharSequence?.clean(): String =
    (this ?: "")
        .take(NAME_LIMIT * 4)
        .filterNot { Character.getType(it) in HIDDEN }
        .trim()
        .take(NAME_LIMIT)
        .toString()

fun CharSequence?.cleanBody(): String {
    val text = (this ?: "").take(BODY_LIMIT)
    val out = StringBuilder(text.length)
    var marks = 0
    for (char in text) {
        if (char in BIDI_OVERRIDES) continue
        if (Character.getType(char) == Character.CONTROL.toInt() &&
            char !in "\n\t"
        ) {
            continue
        }
        marks = if (Character.getType(char) in MARKS) marks + 1 else 0
        if (marks <= MARKS_LIMIT) out.append(char)
    }
    return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

data class Person(
    val address: String,
    val name: String,
    val photo: Uri?,
    val contact: Uri?,
) {
    val known get() = contact != null
}

data class Conversation(
    val id: Long,
    val people: List<Person>,
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val error: Boolean,
) {
    val title get() = people.joinToString { it.name }
    val addresses get() = people.map { it.address }
}

data class Attachment(
    val uri: Uri,
    val type: String,
    val name: String,
) {
    val isImage get() = type in IMAGE_TYPES
}

val IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp")

enum class Box { Inbox, Sent, Outbox, Failed }

data class Message(
    val id: Long,
    val mms: Boolean,
    val thread: Long,
    val address: String,
    val body: String,
    val date: Long,
    val box: Box,
    val delivered: Boolean = false,
    val read: Boolean = true,
    val subscription: Int = -1,
    val attachments: List<Attachment> = emptyList(),
    val pending: Pending? = null,
) {
    val key get() = if (mms) "m$id" else "s$id"
    val uri: Uri get() =
        ContentUris.withAppendedId(
            if (mms) Mms.CONTENT_URI else Sms.CONTENT_URI,
            id,
        )
    val incoming get() = box == Box.Inbox
}

data class Pending(
    val location: String,
    val size: Long,
    val expiry: Long,
    val state: Int,
)

fun ContentResolver.changes(): Flow<Unit> =
    callbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        listOf(
            MmsSms.CONTENT_URI,
            Sms.CONTENT_URI,
            Mms.CONTENT_URI,
            ContactsContract.AUTHORITY_URI,
        ).forEach { runCatching { registerContentObserver(it, true, observer) } }
        send(Unit)
        awaitClose { unregisterContentObserver(observer) }
    }.conflate()

private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))

private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))

private fun <T> Cursor?.list(read: Cursor.() -> T): List<T> =
    this
        ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.read()) } }
        .orEmpty()

class People(
    private val context: Context,
) {
    private val cache = mutableMapOf<String, Person>()

    operator fun get(address: String): Person =
        cache.getOrPut(address) { context.lookup(address) }
}

private fun Context.lookup(address: String): Person {
    val fallback = Person(address, displayAddress(address), null, null)
    if (address.isBlank()) return fallback
    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
    val columns =
        arrayOf(
            PhoneLookup._ID,
            PhoneLookup.LOOKUP_KEY,
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
    return runCatching {
        contentResolver
            .query(uri, columns, null, null, null)
            .list {
                Person(
                    address = address,
                    name =
                        string(
                            PhoneLookup.DISPLAY_NAME,
                        ).clean().ifEmpty { fallback.name },
                    photo = string(PhoneLookup.PHOTO_THUMBNAIL_URI)?.let(Uri::parse),
                    contact =
                        Contacts.getLookupUri(
                            long(PhoneLookup._ID),
                            string(PhoneLookup.LOOKUP_KEY),
                        ),
                )
            }.firstOrNull()
    }.getOrNull() ?: fallback
}

fun Context.countryIso(): String {
    val telephony = getSystemService(TelephonyManager::class.java)
    return telephony.networkCountryIso
        .ifEmpty { telephony.simCountryIso }
        .ifEmpty { Locale.getDefault().country }
        .uppercase(Locale.ROOT)
}

fun Context.displayAddress(address: String): String {
    val clean = address.clean()
    if (clean.isEmpty()) return "Unknown"
    if (!clean.isPhoneNumber()) return clean
    return (PhoneNumberUtils.formatNumber(clean, countryIso()) ?: clean).clean()
}

fun String.isPhoneNumber() = isNotEmpty() && all { it.isDigit() || it in "+ -()." }

fun sameAddress(
    first: String,
    second: String,
) = if (first.isPhoneNumber() && second.isPhoneNumber()) {
    PhoneNumberUtils.areSamePhoneNumber(first, second, "")
} else {
    first.equals(second, ignoreCase = true)
}

private fun ContentResolver.canonicalAddresses(): Map<Long, String> =
    query(CANONICAL_ADDRESSES, null, null, null, null)
        .list {
            long("_id") to string("address").orEmpty()
        }.toMap()

fun Context.conversations(): List<Conversation> {
    val addresses = contentResolver.canonicalAddresses()
    val people = People(this)
    val uri =
        Threads.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(
                "simple",
                "true",
            ).build()
    val columns =
        arrayOf(
            Threads._ID,
            Threads.DATE,
            Threads.RECIPIENT_IDS,
            Threads.SNIPPET,
            Threads.READ,
            Threads.ERROR,
            Threads.MESSAGE_COUNT,
        )
    return contentResolver
        .query(uri, columns, "${Threads.MESSAGE_COUNT} > 0", null, "${Threads.DATE} DESC")
        .list {
            val ids =
                string(
                    Threads.RECIPIENT_IDS,
                ).orEmpty().split(' ').mapNotNull(String::toLongOrNull)
            Conversation(
                id = long(Threads._ID),
                people = ids.mapNotNull(addresses::get).map(people::get),
                snippet = string(Threads.SNIPPET).cleanBody().take(NAME_LIMIT * 2),
                date = long(Threads.DATE),
                read = int(Threads.READ) == 1,
                error = int(Threads.ERROR) != 0,
            )
        }.filter { it.people.isNotEmpty() }
}

fun Context.conversation(thread: Long): Conversation? =
    conversations().find {
        it.id ==
            thread
    }

fun Context.threadFor(addresses: List<String>): Long =
    Threads.getOrCreateThreadId(this, addresses.toSet())

private val SMS_COLUMNS =
    arrayOf(
        Sms._ID,
        Sms.THREAD_ID,
        Sms.ADDRESS,
        Sms.BODY,
        Sms.DATE,
        Sms.TYPE,
        Sms.STATUS,
        Sms.READ,
        Sms.SUBSCRIPTION_ID,
    )

private fun Cursor.sms() =
    Message(
        id = long(Sms._ID),
        mms = false,
        thread = long(Sms.THREAD_ID),
        address = string(Sms.ADDRESS).orEmpty(),
        body = string(Sms.BODY).cleanBody(),
        date = long(Sms.DATE),
        box =
            when (int(Sms.TYPE)) {
                Sms.MESSAGE_TYPE_INBOX -> Box.Inbox
                Sms.MESSAGE_TYPE_SENT -> Box.Sent
                Sms.MESSAGE_TYPE_FAILED -> Box.Failed
                else -> Box.Outbox
            },
        delivered = int(Sms.STATUS) == Sms.STATUS_COMPLETE,
        read = int(Sms.READ) == 1,
        subscription = int(Sms.SUBSCRIPTION_ID),
    )

private val MMS_COLUMNS =
    arrayOf(
        Mms._ID,
        Mms.THREAD_ID,
        Mms.DATE,
        Mms.MESSAGE_BOX,
        Mms.MESSAGE_TYPE,
        Mms.READ,
        Mms.SUBJECT,
        Mms.SUBJECT_CHARSET,
        Mms.CONTENT_LOCATION,
        Mms.MESSAGE_SIZE,
        Mms.EXPIRY,
        Mms.STATUS,
        Mms.SUBSCRIPTION_ID,
    )

private fun Context.mms(cursor: Cursor): Message =
    with(cursor) {
        val id = long(Mms._ID)
        val box =
            when (int(Mms.MESSAGE_BOX)) {
                Mms.MESSAGE_BOX_INBOX -> Box.Inbox
                Mms.MESSAGE_BOX_SENT -> Box.Sent
                Mms.MESSAGE_BOX_FAILED -> Box.Failed
                else -> Box.Outbox
            }
        val notification = int(Mms.MESSAGE_TYPE) == TYPE_NOTIFICATION_IND
        val parts = if (notification) emptyList() else contentResolver.parts(id)
        val text =
            parts.filter { it.first.type == "text/plain" }.joinToString(
                "\n",
            ) { it.second }
        val subject = string(Mms.SUBJECT).orEmpty()
        Message(
            id = id,
            mms = true,
            thread = long(Mms.THREAD_ID),
            address = if (box == Box.Inbox) contentResolver.sender(id) else "",
            body =
                listOf(
                    subject,
                    text,
                ).filter { it.isNotBlank() }.joinToString("\n").cleanBody(),
            date = long(Mms.DATE) * 1000,
            box = box,
            read = int(Mms.READ) == 1,
            subscription = int(Mms.SUBSCRIPTION_ID),
            attachments =
                parts.map { it.first }.filter {
                    it.type != "text/plain" &&
                        it.type != "application/smil"
                },
            pending =
                if (notification) {
                    Pending(
                        location = string(Mms.CONTENT_LOCATION).orEmpty(),
                        size = long(Mms.MESSAGE_SIZE),
                        expiry = long(Mms.EXPIRY),
                        state = int(Mms.STATUS),
                    )
                } else {
                    null
                },
        )
    }

private fun ContentResolver.parts(id: Long): List<Pair<Attachment, String>> =
    query(
        Uri.parse("content://mms/part"),
        arrayOf(
            Mms.Part._ID,
            Mms.Part.CONTENT_TYPE,
            Mms.Part.TEXT,
            Mms.Part.NAME,
            Mms.Part.FILENAME,
        ),
        "${Mms.Part.MSG_ID} = ?",
        arrayOf(id.toString()),
        "${Mms.Part.SEQ} ASC",
    ).list {
        val attachment =
            Attachment(
                uri = Uri.parse("content://mms/part/${long(Mms.Part._ID)}"),
                type = string(Mms.Part.CONTENT_TYPE).orEmpty().lowercase(Locale.ROOT),
                name = (string(Mms.Part.NAME) ?: string(Mms.Part.FILENAME)).clean(),
            )
        attachment to string(Mms.Part.TEXT).orEmpty()
    }

private fun ContentResolver.sender(id: Long): String =
    query(
        Uri.parse("content://mms/$id/addr"),
        arrayOf(Mms.Addr.ADDRESS),
        "${Mms.Addr.TYPE} = $ADDRESS_FROM",
        null,
        null,
    ).list { string(Mms.Addr.ADDRESS).orEmpty() }
        .firstOrNull()
        .orEmpty()

fun Context.messages(thread: Long): List<Message> {
    val args = arrayOf(thread.toString())
    val sms =
        contentResolver
            .query(
                Sms.CONTENT_URI,
                SMS_COLUMNS,
                "${Sms.THREAD_ID} = ?",
                args,
                "${Sms.DATE} DESC LIMIT $THREAD_LIMIT",
            ).list { sms() }
    val mms =
        contentResolver
            .query(
                Mms.CONTENT_URI,
                MMS_COLUMNS,
                "${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_TYPE} IN (${TYPE_SEND_REQ}, " +
                    "$TYPE_NOTIFICATION_IND, $TYPE_RETRIEVE_CONF)",
                args,
                "${Mms.DATE} DESC LIMIT $THREAD_LIMIT",
            ).list { mms(this) }
    return (sms + mms).sortedBy { it.date }.takeLast(THREAD_LIMIT)
}

fun Context.message(uri: Uri): Message? {
    val mms = uri.authority == "mms"
    val columns = if (mms) MMS_COLUMNS else SMS_COLUMNS
    return contentResolver
        .query(uri, columns, null, null, null)
        .list { if (mms) mms(this) else sms() }
        .firstOrNull()
}

fun Context.unread(thread: Long): List<Message> =
    messages(thread).filter { it.incoming && !it.read }

fun Context.search(query: String): List<Message> {
    if (query.isBlank()) return emptyList()
    val pattern =
        "%" +
            query
                .trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") +
            "%"
    return contentResolver
        .query(
            Sms.CONTENT_URI,
            SMS_COLUMNS,
            "${Sms.BODY} LIKE ? ESCAPE '\\'",
            arrayOf(pattern),
            "${Sms.DATE} DESC LIMIT $SEARCH_LIMIT",
        ).list { sms() }
}

fun ContentResolver.markRead(thread: Long) {
    val values = contentValuesOf(Sms.READ to 1, Sms.SEEN to 1)
    val where = "${Sms.THREAD_ID} = ? AND (${Sms.READ} = 0 OR ${Sms.SEEN} = 0)"
    val args = arrayOf(thread.toString())
    runCatching { update(Sms.CONTENT_URI, values, where, args) }
    runCatching { update(Mms.CONTENT_URI, values, where, args) }
}

fun ContentResolver.markUnread(thread: Long) {
    val latest =
        query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID),
            "${Sms.THREAD_ID} = ? AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}",
            arrayOf(thread.toString()),
            "${Sms.DATE} DESC LIMIT 1",
        ).list { long(Sms._ID) }.firstOrNull() ?: return
    update(
        ContentUris.withAppendedId(Sms.CONTENT_URI, latest),
        contentValuesOf(Sms.READ to 0),
        null,
        null,
    )
}

fun ContentResolver.deleteThreads(threads: Collection<Long>) {
    threads.forEach {
        delete(ContentUris.withAppendedId(Threads.CONTENT_URI, it), null, null)
    }
}

fun ContentResolver.deleteMessage(message: Message) = delete(message.uri, null, null)

fun Context.isBlocked(address: String) =
    runCatching { BlockedNumberContract.isBlocked(this, address) }.getOrDefault(false)

fun Context.setBlocked(
    address: String,
    blocked: Boolean,
) = runCatching {
    if (blocked) {
        val values = contentValuesOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER to address)
        contentResolver.insert(BlockedNumbers.CONTENT_URI, values)
    } else {
        BlockedNumberContract.unblock(this, address)
    }
}.isSuccess

data class Contact(
    val name: String,
    val number: String,
    val label: String,
    val photo: Uri?,
)

fun Context.contacts(query: String): List<Contact> {
    val uri =
        if (query.isBlank()) {
            Phone.CONTENT_URI
        } else {
            Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        }
    val columns =
        arrayOf(
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.PHOTO_THUMBNAIL_URI,
        )
    return contentResolver
        .query(uri, columns, null, null, "${Phone.SORT_KEY_PRIMARY} COLLATE LOCALIZED")
        .list {
            Contact(
                name = string(Phone.DISPLAY_NAME_PRIMARY).clean(),
                number = string(Phone.NUMBER).clean(),
                label =
                    Phone
                        .getTypeLabel(
                            resources,
                            int(Phone.TYPE),
                            string(Phone.LABEL),
                        ).clean(),
                photo = string(Phone.PHOTO_THUMBNAIL_URI)?.let(Uri::parse),
            )
        }.distinctBy { it.name to PhoneNumberUtils.normalizeNumber(it.number) }
}

const val ADDRESS_FROM = 137
const val ADDRESS_TO = 151

val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "👎")
private const val QUOTE_LIMIT = 60
private val GOOGLE_REACTION =
    Regex(
        """^(Reacted|Removed) (\S{1,16}) (?:to|from) [“"](.+)[”"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )
private val APPLE_REACTION =
    Regex(
        """^(Liked|Loved|Laughed at|Emphasized|Questioned|Disliked) [“"](.+)[”"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )
private val APPLE_EMOJI =
    mapOf(
        "Liked" to "👍",
        "Loved" to "❤️",
        "Laughed at" to "😂",
        "Emphasized" to "‼️",
        "Questioned" to "❓",
        "Disliked" to "👎",
    )

data class Reaction(
    val emoji: String,
    val quote: String,
    val removed: Boolean,
)

fun parseReaction(body: String): Reaction? {
    GOOGLE_REACTION.matchEntire(body)?.let {
        val (verb, emoji, quote) = it.destructured
        return Reaction(emoji, quote.removeSuffix("…"), verb == "Removed")
    }
    APPLE_REACTION.matchEntire(body)?.let {
        val (verb, quote) = it.destructured
        return Reaction(APPLE_EMOJI.getValue(verb), quote.removeSuffix("…"), false)
    }
    return null
}

fun reactionText(
    emoji: String,
    body: String,
): String {
    val quote = if (body.length > QUOTE_LIMIT) body.take(QUOTE_LIMIT) + "…" else body
    return "Reacted $emoji to “$quote”"
}

data class Shown(
    val message: Message,
    val reactions: List<String>,
)

fun List<Message>.withReactions(): List<Shown> {
    val reactions = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val shown = mutableListOf<Message>()
    for (message in this) {
        val reaction = parseReaction(message.body)
        val target =
            reaction?.let { parsed ->
                shown.lastOrNull {
                    it.body.isNotEmpty() &&
                        it.body.startsWith(parsed.quote)
                }
            }
        if (reaction == null || target == null) {
            shown += message
            continue
        }
        val list = reactions.getOrPut(target.key) { mutableListOf() }
        val sender = message.address.ifEmpty { "me" }
        list.removeAll { it.first == sender }
        if (!reaction.removed) list += sender to reaction.emoji
    }
    return shown.map { message ->
        Shown(message, reactions[message.key].orEmpty().map { it.second })
    }
}
