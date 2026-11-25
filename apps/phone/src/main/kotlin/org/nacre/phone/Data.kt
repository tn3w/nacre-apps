package org.nacre.phone

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.CallLog
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.text.Normalizer
import java.util.Locale

private const val T9 = "22233344455566677778889999"
private const val LOG_LIMIT = "500"
private const val MATCH_LIMIT = 30

data class Contact(
    val id: Long,
    val lookup: String,
    val name: String,
    val photo: Uri?,
    val starred: Boolean,
) {
    val uri: Uri
        get() =
            Contacts.getLookupUri(id, lookup)
                ?: ContentUris.withAppendedId(Contacts.CONTENT_URI, id)
}

data class Number(
    val contact: Contact,
    val number: String,
    val label: String,
)

data class Entry(
    val id: Long,
    val number: String,
    val name: String,
    val photo: Uri?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val presentation: Int,
    val detail: String,
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
            ContactsContract.AUTHORITY_URI,
            CallLog.CONTENT_URI,
            BlockedNumbers.CONTENT_URI,
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

private fun Cursor.contact(idColumn: String = Contacts._ID) =
    Contact(
        id = long(idColumn),
        lookup = string(Contacts.LOOKUP_KEY).orEmpty(),
        name = string(Contacts.DISPLAY_NAME_PRIMARY).orEmpty(),
        photo = string(Contacts.PHOTO_THUMBNAIL_URI)?.let(Uri::parse),
        starred = int(Contacts.STARRED) == 1,
    )

private val CONTACT_COLUMNS =
    arrayOf(
        Contacts._ID,
        Contacts.LOOKUP_KEY,
        Contacts.DISPLAY_NAME_PRIMARY,
        Contacts.PHOTO_THUMBNAIL_URI,
        Contacts.STARRED,
    )

fun ContentResolver.contacts(
    query: String = "",
    starred: Boolean = false,
): List<Contact> {
    val uri =
        if (query.isBlank()) {
            Contacts.CONTENT_URI
        } else {
            Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        }
    val selection =
        "${Contacts.HAS_PHONE_NUMBER} = 1" +
            if (starred) " AND ${Contacts.STARRED} = 1" else ""
    val order = "${Contacts.SORT_KEY_PRIMARY} COLLATE LOCALIZED"
    return query(uri, CONTACT_COLUMNS, selection, null, order).list { contact() }
}

private val NUMBER_COLUMNS =
    arrayOf(
        Phone.CONTACT_ID,
        Phone.LOOKUP_KEY,
        Phone.DISPLAY_NAME_PRIMARY,
        Phone.PHOTO_THUMBNAIL_URI,
        Phone.STARRED,
        Phone.NUMBER,
        Phone.TYPE,
        Phone.LABEL,
        Phone.IS_SUPER_PRIMARY,
    )

private fun Cursor.number(context: Context) =
    Number(
        contact = contact(Phone.CONTACT_ID),
        number = string(Phone.NUMBER).orEmpty(),
        label =
            Phone
                .getTypeLabel(context.resources, int(Phone.TYPE), string(Phone.LABEL))
                .toString(),
    )

fun Context.numbers(contactId: Long): List<Number> =
    contentResolver
        .query(
            Phone.CONTENT_URI,
            NUMBER_COLUMNS,
            "${Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${Phone.IS_SUPER_PRIMARY} DESC",
        ).list { number(this@numbers) }
        .distinctBy { PhoneNumberUtils.normalizeNumber(it.number) }

fun Context.matches(digits: String): List<Number> {
    if (digits.isEmpty()) return emptyList()
    val order = "${Phone.STARRED} DESC, ${Phone.SORT_KEY_PRIMARY} COLLATE LOCALIZED"
    return contentResolver
        .query(Phone.CONTENT_URI, NUMBER_COLUMNS, null, null, order)
        .list { number(this@matches) }
        .filter {
            digits in
                PhoneNumberUtils.normalizeNumber(
                    it.number,
                ) || t9(it, digits)
        }.take(MATCH_LIMIT)
}

private fun t9(
    number: Number,
    digits: String,
): Boolean {
    val plain =
        Normalizer
            .normalize(number.contact.name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase(Locale.ROOT)
    val keys =
        plain
            .map { char ->
                when (char) {
                    in 'a'..'z' -> T9[char - 'a']
                    in '0'..'9' -> char
                    else -> ' '
                }
            }.joinToString("")
    return keys.split(' ').any { it.startsWith(digits) } ||
        keys.replace(" ", "").startsWith(digits)
}

fun Context.lookup(number: String): Number? {
    if (number.isBlank()) return null
    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val columns =
        arrayOf(
            PhoneLookup._ID,
            PhoneLookup.LOOKUP_KEY,
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.PHOTO_THUMBNAIL_URI,
            PhoneLookup.STARRED,
            PhoneLookup.TYPE,
            PhoneLookup.LABEL,
        )
    return runCatching {
        contentResolver
            .query(uri, columns, null, null, null)
            .list {
                Number(
                    contact = contact(PhoneLookup._ID),
                    number = number,
                    label =
                        Phone
                            .getTypeLabel(
                                resources,
                                int(PhoneLookup.TYPE),
                                string(PhoneLookup.LABEL),
                            ).toString(),
                )
            }.firstOrNull()
    }.getOrNull()
}

private val LOG_COLUMNS =
    arrayOf(
        Calls._ID,
        Calls.NUMBER,
        Calls.CACHED_NAME,
        Calls.CACHED_PHOTO_URI,
        Calls.CACHED_NUMBER_TYPE,
        Calls.CACHED_NUMBER_LABEL,
        Calls.GEOCODED_LOCATION,
        Calls.TYPE,
        Calls.DATE,
        Calls.DURATION,
        Calls.NUMBER_PRESENTATION,
    )

fun Context.callLog(number: String? = null): List<Entry> {
    val uri =
        Calls.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(Calls.LIMIT_PARAM_KEY, LOG_LIMIT)
            .build()
    val selection = number?.let { "${Calls.NUMBER} = ?" }
    val arguments = number?.let { arrayOf(it) }
    return contentResolver
        .query(
            uri,
            LOG_COLUMNS,
            selection,
            arguments,
            "${Calls.DATE} DESC",
        ).list {
            val type = int(Calls.CACHED_NUMBER_TYPE)
            val label = string(Calls.CACHED_NUMBER_LABEL)
            val name = string(Calls.CACHED_NAME).orEmpty()
            Entry(
                id = long(Calls._ID),
                number = string(Calls.NUMBER).orEmpty(),
                name = name,
                photo = string(Calls.CACHED_PHOTO_URI)?.let(Uri::parse),
                type = int(Calls.TYPE),
                date = long(Calls.DATE),
                duration = long(Calls.DURATION),
                presentation = int(Calls.NUMBER_PRESENTATION),
                detail =
                    when {
                        name.isEmpty() -> string(Calls.GEOCODED_LOCATION).orEmpty()
                        type == 0 && label.isNullOrEmpty() -> ""
                        else -> Phone.getTypeLabel(resources, type, label).toString()
                    },
            )
        }
}

fun List<Entry>.grouped(): List<List<Entry>> {
    val groups = mutableListOf<MutableList<Entry>>()
    for (entry in this) {
        val group = groups.lastOrNull()
        if (group != null && group.first().sameCaller(entry)) {
            group += entry
        } else {
            groups += mutableListOf(entry)
        }
    }
    return groups
}

private fun Entry.sameCaller(other: Entry) =
    presentation == other.presentation &&
        PhoneNumberUtils.normalizeNumber(number) ==
        PhoneNumberUtils.normalizeNumber(other.number)

fun ContentResolver.deleteCalls(ids: List<Long>) {
    if (ids.isEmpty()) return
    delete(Calls.CONTENT_URI, "${Calls._ID} IN (${ids.joinToString(",")})", null)
}

fun ContentResolver.clearCallLog() = delete(Calls.CONTENT_URI, null, null)

fun ContentResolver.markMissedRead() {
    val values = contentValuesOf(Calls.IS_READ to 1, Calls.NEW to 0)
    val where =
        "${Calls.TYPE} = ${Calls.MISSED_TYPE} AND " +
            "(${Calls.IS_READ} = 0 OR ${Calls.NEW} = 1)"
    runCatching { update(Calls.CONTENT_URI, values, where, null) }
}

fun Context.isBlocked(number: String) =
    runCatching { BlockedNumberContract.isBlocked(this, number) }.getOrDefault(false)

fun Context.setBlocked(
    number: String,
    blocked: Boolean,
) = runCatching {
    if (blocked) {
        val values = contentValuesOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER to number)
        contentResolver.insert(BlockedNumbers.CONTENT_URI, values)
    } else {
        BlockedNumberContract.unblock(this, number)
    }
}.isSuccess

fun Context.countryIso(): String {
    val telephony = getSystemService(TelephonyManager::class.java)
    return telephony.networkCountryIso
        .ifEmpty { telephony.simCountryIso }
        .ifEmpty { Locale.getDefault().country }
        .uppercase(Locale.ROOT)
}

fun Context.formatNumber(number: String): String =
    PhoneNumberUtils.formatNumber(number, countryIso()) ?: number
