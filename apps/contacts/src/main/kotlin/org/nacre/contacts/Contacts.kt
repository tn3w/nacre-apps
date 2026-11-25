package org.nacre.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale
import android.content.res.Resources
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

private const val TYPE = Data.DATA2
private const val LABEL = Data.DATA3

enum class Kind(
    val mime: String,
    val column: String,
    val extraColumn: String? = null,
    val types: List<Int> = emptyList(),
    val contentUri: Uri? = null
) {
    Name(
        CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        CommonDataKinds.StructuredName.GIVEN_NAME,
        CommonDataKinds.StructuredName.FAMILY_NAME
    ),
    Nickname(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, CommonDataKinds.Nickname.NAME),
    Organization(
        CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        CommonDataKinds.Organization.COMPANY,
        CommonDataKinds.Organization.TITLE
    ),
    Phone(
        CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        CommonDataKinds.Phone.NUMBER,
        types = listOf(
            CommonDataKinds.Phone.TYPE_MOBILE,
            CommonDataKinds.Phone.TYPE_HOME,
            CommonDataKinds.Phone.TYPE_WORK,
            CommonDataKinds.Phone.TYPE_OTHER
        ),
        contentUri = CommonDataKinds.Phone.CONTENT_URI
    ),
    Email(
        CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        CommonDataKinds.Email.ADDRESS,
        types = listOf(
            CommonDataKinds.Email.TYPE_HOME,
            CommonDataKinds.Email.TYPE_WORK,
            CommonDataKinds.Email.TYPE_OTHER
        ),
        contentUri = CommonDataKinds.Email.CONTENT_URI
    ),
    Address(
        CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
        types = listOf(
            CommonDataKinds.StructuredPostal.TYPE_HOME,
            CommonDataKinds.StructuredPostal.TYPE_WORK,
            CommonDataKinds.StructuredPostal.TYPE_OTHER
        ),
        contentUri = CommonDataKinds.StructuredPostal.CONTENT_URI
    ),
    Website(CommonDataKinds.Website.CONTENT_ITEM_TYPE, CommonDataKinds.Website.URL),
    Birthday(
        CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        CommonDataKinds.Event.START_DATE,
        types = listOf(CommonDataKinds.Event.TYPE_BIRTHDAY)
    ),
    Note(CommonDataKinds.Note.CONTENT_ITEM_TYPE, CommonDataKinds.Note.NOTE);

    val single get() = this in listOf(Name, Nickname, Organization, Birthday, Note)

    fun label(resources: Resources, type: Int, label: String?): String = when (this) {
        Phone -> CommonDataKinds.Phone.getTypeLabel(resources, type, label)
        Email -> CommonDataKinds.Email.getTypeLabel(resources, type, label)
        Address -> CommonDataKinds.StructuredPostal.getTypeLabel(resources, type, label)
        else -> name
    }.toString()

    companion object {
        fun of(mime: String) = entries.find { it.mime == mime }
    }
}

data class Row(
    val kind: Kind,
    val value: String = "",
    val extra: String = "",
    val type: Int = kind.types.firstOrNull() ?: 0,
    val label: String? = null,
    val id: Long? = null
) {
    val blank get() = value.isBlank() && extra.isBlank()
}

data class Contact(
    val id: Long,
    val lookup: String,
    val name: String,
    val photo: Uri?,
    val starred: Boolean
) {
    val uri: Uri get() = Contacts.getLookupUri(id, lookup)
}

data class Details(
    val contact: Contact,
    val rawId: Long?,
    val photo: Uri?,
    val rows: List<Row>
)

data class Pickable(val uri: Uri, val contact: Contact, val detail: String)

fun ContentResolver.changes(): Flow<Unit> = callbackFlow {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            trySend(Unit)
        }
    }
    registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
    send(Unit)
    awaitClose { unregisterContentObserver(observer) }
}.conflate()

private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))

private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))

private fun <T> Cursor?.list(read: Cursor.() -> T): List<T> =
    this?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.read()) } }
        .orEmpty()

private fun nameColumn(lastFirst: Boolean) =
    if (lastFirst) Contacts.DISPLAY_NAME_ALTERNATIVE else Contacts.DISPLAY_NAME_PRIMARY

private fun Cursor.contact(nameColumn: String, idColumn: String = Contacts._ID) = Contact(
    id = long(idColumn),
    lookup = string(Contacts.LOOKUP_KEY).orEmpty(),
    name = string(nameColumn) ?: "",
    photo = string(Contacts.PHOTO_THUMBNAIL_URI)?.let(Uri::parse),
    starred = int(Contacts.STARRED) == 1
)

fun ContentResolver.contacts(
    query: String = "",
    sortByLast: Boolean = Store.sortByLast.value,
    lastFirst: Boolean = Store.lastFirst.value
): List<Contact> {
    val uri = if (query.isBlank()) {
        Contacts.CONTENT_URI
    } else {
        Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
    }
    val name = nameColumn(lastFirst)
    val projection = arrayOf(
        Contacts._ID,
        Contacts.LOOKUP_KEY,
        name,
        Contacts.PHOTO_THUMBNAIL_URI,
        Contacts.STARRED
    )
    val order = if (sortByLast) Contacts.SORT_KEY_ALTERNATIVE else Contacts.SORT_KEY_PRIMARY
    return query(uri, projection, null, null, "$order COLLATE LOCALIZED")
        .list { contact(name) }
}

fun ContentResolver.pickables(kind: Kind, query: String): List<Pickable> {
    val uri = kind.contentUri ?: return emptyList()
    val name = nameColumn(Store.lastFirst.value)
    val projection = arrayOf(
        Data._ID,
        Data.CONTACT_ID,
        Contacts.LOOKUP_KEY,
        name,
        Contacts.PHOTO_THUMBNAIL_URI,
        Contacts.STARRED,
        kind.column,
        TYPE,
        LABEL
    )
    val order = if (Store.sortByLast.value) Contacts.SORT_KEY_ALTERNATIVE else Contacts.SORT_KEY_PRIMARY
    return query(uri, projection, null, null, "$order COLLATE LOCALIZED").list {
        val label = kind.label(Resources.getSystem(), int(TYPE), string(LABEL))
        Pickable(
            ContentUris.withAppendedId(uri, long(Data._ID)),
            contact(name, Data.CONTACT_ID),
            "${string(kind.column).orEmpty()} · $label"
        )
    }.filter {
        query.isBlank() ||
            it.contact.name.contains(query.trim(), ignoreCase = true) ||
            it.detail.contains(query.trim(), ignoreCase = true)
    }
}

fun ContentResolver.contactId(uri: Uri): Long? {
    val raw = uri.authority == "contacts" || uri.pathSegments.firstOrNull() == "raw_contacts"
    val column = if (raw) RawContacts.CONTACT_ID else Contacts._ID
    val target = if (raw) ContentUris.withAppendedId(RawContacts.CONTENT_URI, uri.lastId())
    else uri
    return runCatching {
        query(target, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }.getOrNull()
}

private fun Uri.lastId() = lastPathSegment?.toLongOrNull() ?: -1

fun ContentResolver.lookupPhone(number: String): Long? = query(
    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
    arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
    null,
    null,
    null
)?.use { if (it.moveToFirst()) it.getLong(0) else null }

fun ContentResolver.lookupEmail(address: String): Long? = query(
    Uri.withAppendedPath(CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(address)),
    arrayOf(Data.CONTACT_ID),
    null,
    null,
    null
)?.use { if (it.moveToFirst()) it.getLong(0) else null }

fun ContentResolver.details(contactId: Long): Details? {
    val uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)
    val projection = arrayOf(
        Contacts._ID,
        Contacts.LOOKUP_KEY,
        Contacts.DISPLAY_NAME_PRIMARY,
        Contacts.PHOTO_THUMBNAIL_URI,
        Contacts.PHOTO_URI,
        Contacts.STARRED
    )
    val (contact, photo) = query(uri, projection, null, null, null)?.use {
        if (!it.moveToFirst()) return null
        it.contact(Contacts.DISPLAY_NAME_PRIMARY) to
            it.string(Contacts.PHOTO_URI)?.let(Uri::parse)
    } ?: return null
    val rawIds = query(
        RawContacts.CONTENT_URI,
        arrayOf(RawContacts._ID),
        "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
        arrayOf("$contactId"),
        RawContacts._ID
    ).list { getLong(0) }
    val rows = rows("${Data.CONTACT_ID} = ?", contactId)
        .distinctBy { Triple(it.kind, it.value.filterNot(Char::isWhitespace), it.extra.trim()) }
        .sortedBy { it.kind.ordinal }
    return Details(contact, rawIds.firstOrNull(), photo, rows)
}

fun ContentResolver.rawRows(rawId: Long) = rows("${Data.RAW_CONTACT_ID} = ?", rawId)

private fun ContentResolver.rows(selection: String, id: Long): List<Row> {
    val columns = Kind.entries.flatMap { listOfNotNull(it.column, it.extraColumn) }
    val projection = (listOf(Data._ID, Data.MIMETYPE, TYPE, LABEL) + columns).distinct()
    return query(
        Data.CONTENT_URI,
        projection.toTypedArray(),
        selection,
        arrayOf("$id"),
        "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}"
    ).list {
        val kind = Kind.of(string(Data.MIMETYPE)) ?: return@list null
        val type = if (kind.types.isEmpty()) 0 else int(TYPE)
        if (kind.types.size == 1 && type != kind.types.single()) return@list null
        Row(
            kind = kind,
            value = string(kind.column).orEmpty(),
            extra = kind.extraColumn?.let { string(it) }.orEmpty(),
            type = type,
            label = if (kind.types.isEmpty()) null else string(LABEL),
            id = long(Data._ID)
        ).takeUnless { it.blank }
    }.filterNotNull()
}

fun ContentResolver.save(rawId: Long?, original: List<Row>, rows: List<Row>): Long {
    val operations = ArrayList<ContentProviderOperation>()
    if (rawId == null) {
        operations += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null)
            .withValue(RawContacts.ACCOUNT_NAME, null)
            .build()
    }
    val kept = rows.filterNot { it.blank }
    original.filter { row -> kept.none { it.id == row.id } }.forEach { row ->
        operations += ContentProviderOperation
            .newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, row.id!!))
            .build()
    }
    kept.forEach { row ->
        val builder = when (row.id) {
            null -> ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.MIMETYPE, row.kind.mime)
                .also {
                    if (rawId == null) it.withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    else it.withValue(Data.RAW_CONTACT_ID, rawId)
                }

            else -> {
                if (original.find { it.id == row.id } == row) return@forEach
                ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, row.id))
            }
        }
        builder.withValue(row.kind.column, row.value.trim())
        row.kind.extraColumn?.let { builder.withValue(it, row.extra.trim()) }
        if (row.kind.types.isNotEmpty()) builder.withValue(TYPE, row.type)
        operations += builder.build()
    }
    val results = applyBatch(ContactsContract.AUTHORITY, operations)
    return rawId ?: ContentUris.parseId(results[0].uri!!)
}

fun ContentResolver.lookupUri(rawId: Long): Uri? =
    RawContacts.getContactLookupUri(this, ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId))

fun ContentResolver.setPhoto(rawId: Long, jpeg: ByteArray?) {
    if (jpeg == null) {
        delete(
            Data.CONTENT_URI,
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf("$rawId", CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
        )
        return
    }
    val uri = Uri.withAppendedPath(
        ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
        RawContacts.DisplayPhoto.CONTENT_DIRECTORY
    )
    openAssetFileDescriptor(uri, "rw")?.use { descriptor ->
        descriptor.createOutputStream().use { it.write(jpeg) }
    }
}

fun ContentResolver.setStarred(contactId: Long, starred: Boolean) {
    val values = ContentValues().apply { put(Contacts.STARRED, starred) }
    update(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), values, null, null)
}

fun ContentResolver.deleteContact(contact: Contact) {
    delete(contact.uri, null, null)
}

fun Context.formatPhone(number: String): String {
    val telephony = getSystemService(TelephonyManager::class.java)
    val country = listOfNotNull(telephony?.networkCountryIso, telephony?.simCountryIso)
        .firstOrNull { it.isNotBlank() } ?: Locale.getDefault().country
    return PhoneNumberUtils.formatNumber(number.trim(), country.uppercase()) ?: number.trim()
}
