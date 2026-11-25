package org.nacre.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream

private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
private const val MAX_PHOTO_BYTES = 512 * 1024
private const val EXPORT_CHUNK = 200
private val ESCAPE = Regex("\\\\(.)")

private data class Property(
    val name: String,
    val parameters: List<String>,
    val value: String
) {
    val types = parameters.flatMap { parameter ->
        parameter.substringAfter('=').uppercase().split(',').map { it.trim('"', ' ') }
    }
    val parts get() = splitUnescaped(value).map(::unescape)
}

fun ContentResolver.exportVcards(target: Uri): Int {
    val keys = contacts().map { it.lookup }.filter { it.isNotEmpty() }
    openOutputStream(target, "wt")?.use { output ->
        keys.chunked(EXPORT_CHUNK).forEach { chunk -> writeChunk(chunk, output) }
    } ?: error("Cannot open $target")
    return keys.size
}

private fun ContentResolver.writeChunk(keys: List<String>, output: OutputStream) {
    val uri = Uri.withAppendedPath(
        Contacts.CONTENT_MULTI_VCARD_URI,
        Uri.encode(keys.joinToString(":"))
    )
    openInputStream(uri)?.use { it.copyTo(output) }
}

fun ContentResolver.countVcards(source: Uri): Int = parse(read(source)).size

fun ContentResolver.importVcards(source: Uri): Int = parse(read(source)).count { card ->
    val operations = ArrayList(operations(card))
    operations.size > 1 &&
        runCatching { applyBatch(ContactsContract.AUTHORITY, operations) }.isSuccess
}

private fun ContentResolver.read(source: Uri): String {
    val bytes = openInputStream(source)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_IMPORT_BYTES) { "File too large" }
        }
        output.toByteArray()
    } ?: error("Cannot open $source")
    return bytes.decodeToString()
}

private fun parse(text: String): List<List<Property>> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("\n[ \t]"), "")
        .split('\n')
    val cards = mutableListOf<List<Property>>()
    var card: MutableList<Property>? = null
    var index = 0
    while (index < lines.size) {
        var line = lines[index++]
        val quoted = line.substringBefore(':').contains("QUOTED-PRINTABLE", true)
        while (quoted && line.endsWith('=') && index < lines.size) {
            line = line.dropLast(1) + lines[index++]
        }
        val property = property(line, quoted) ?: continue
        when {
            property.name == "BEGIN" -> card = mutableListOf()

            property.name == "END" -> {
                card?.let(cards::add)
                card = null
            }

            else -> card?.add(property)
        }
    }
    return cards
}

private fun property(line: String, quoted: Boolean): Property? {
    val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
    val head = line.substring(0, colon).split(';')
    val name = head.first().substringAfterLast('.').uppercase()
    val raw = line.substring(colon + 1)
    val value = if (quoted) decodeQuotedPrintable(raw) else raw
    return Property(name, head.drop(1), value)
}

private fun decodeQuotedPrintable(text: String): String {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < text.length) {
        val hex = text.takeIf { index + 2 < it.length }
            ?.substring(index + 1, index + 3)?.toIntOrNull(16)
        if (text[index] == '=' && hex != null) {
            bytes.write(hex)
            index += 3
        } else {
            bytes.write(text[index].toString().encodeToByteArray())
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun splitUnescaped(value: String): List<String> {
    val parts = mutableListOf(StringBuilder())
    var escaped = false
    value.forEach { character ->
        when {
            escaped -> parts.last().append('\\').append(character).also {
                escaped = false
            }

            character == '\\' -> escaped = true

            character == ';' -> parts += StringBuilder()

            else -> parts.last().append(character)
        }
    }
    return parts.map { it.toString() }
}

private fun unescape(value: String) = ESCAPE.replace(value) { match ->
    val character = match.groupValues[1]
    if (character == "n" || character == "N") "\n" else character
}

private fun operations(card: List<Property>): List<ContentProviderOperation> {
    val operations = mutableListOf(
        ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, null)
            .withValue(RawContacts.ACCOUNT_NAME, null)
            .build()
    )

    fun add(mime: String, vararg values: Pair<String, Any?>) {
        if (values.all { (_, value) -> value == null || value == "" }) return
        val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, mime)
        values.forEach { (column, value) -> builder.withValue(column, value) }
        operations += builder.build()
    }

    fun first(name: String) = card.find { it.name == name }

    val structured = first("N")?.parts.orEmpty() + List(5) { "" }
    val formatted = first("FN")?.parts?.joinToString(";").orEmpty()
    if (structured.take(5).any { it.isNotBlank() }) {
        add(
            Kind.Name.mime,
            StructuredName.FAMILY_NAME to structured[0],
            StructuredName.GIVEN_NAME to structured[1],
            StructuredName.MIDDLE_NAME to structured[2],
            StructuredName.PREFIX to structured[3],
            StructuredName.SUFFIX to structured[4]
        )
    } else {
        add(Kind.Name.mime, StructuredName.DISPLAY_NAME to formatted)
    }

    val company = first("ORG")?.parts?.firstOrNull().orEmpty()
    val title = first("TITLE")?.parts?.joinToString(";").orEmpty()
    add(
        CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        CommonDataKinds.Organization.COMPANY to company,
        CommonDataKinds.Organization.TITLE to title
    )

    card.forEach { property ->
        val text = property.parts.joinToString(";").trim()
        when (property.name) {
            "TEL" -> add(
                Kind.Phone.mime,
                CommonDataKinds.Phone.NUMBER to text.removePrefix("tel:"),
                CommonDataKinds.Phone.TYPE to phoneType(property.types)
            )

            "EMAIL" -> add(
                Kind.Email.mime,
                CommonDataKinds.Email.ADDRESS to text,
                CommonDataKinds.Email.TYPE to placeType(property.types)
            )

            "ADR" -> add(Kind.Address.mime, *address(property))

            "URL" -> add(Kind.Website.mime, CommonDataKinds.Website.URL to text)

            "NOTE" -> add(Kind.Note.mime, CommonDataKinds.Note.NOTE to text)

            "NICKNAME" -> add(Kind.Nickname.mime, CommonDataKinds.Nickname.NAME to text)

            "BDAY" -> add(
                Kind.Birthday.mime,
                CommonDataKinds.Event.START_DATE to birthday(text),
                CommonDataKinds.Event.TYPE to CommonDataKinds.Event.TYPE_BIRTHDAY
            )

            "PHOTO" -> photo(property)?.let {
                add(
                    CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                    CommonDataKinds.Photo.PHOTO to it
                )
            }
        }
    }
    return operations
}

private fun address(property: Property): Array<Pair<String, Any?>> {
    val parts = property.parts + List(7) { "" }
    val street = listOf(parts[2], parts[1]).filter { it.isNotBlank() }.joinToString("\n")
    return arrayOf(
        StructuredPostal.POBOX to parts[0],
        StructuredPostal.STREET to street,
        StructuredPostal.CITY to parts[3],
        StructuredPostal.REGION to parts[4],
        StructuredPostal.POSTCODE to parts[5],
        StructuredPostal.COUNTRY to parts[6],
        StructuredPostal.TYPE to placeType(property.types)
    )
}

private fun phoneType(types: List<String>) = when {
    "CELL" in types -> Phone.TYPE_MOBILE
    "FAX" in types -> if ("HOME" in types) Phone.TYPE_FAX_HOME else Phone.TYPE_FAX_WORK
    "HOME" in types -> Phone.TYPE_HOME
    "WORK" in types -> Phone.TYPE_WORK
    "PAGER" in types -> Phone.TYPE_PAGER
    "MAIN" in types -> Phone.TYPE_MAIN
    else -> Phone.TYPE_OTHER
}

private fun placeType(types: List<String>) = when {
    "HOME" in types -> Email.TYPE_HOME
    "WORK" in types -> Email.TYPE_WORK
    else -> Email.TYPE_OTHER
}

private fun birthday(text: String): String {
    val digits = text.substringBefore('T').replace("-", "")
    return when {
        digits.length == 8 && digits.all(Char::isDigit) ->
            "${digits.take(4)}-${digits.substring(4, 6)}-${digits.takeLast(2)}"

        text.startsWith("--") && digits.length == 4 ->
            "--${digits.take(2)}-${digits.takeLast(2)}"

        else -> text
    }
}

private fun photo(property: Property): ByteArray? {
    val encoded = property.value.substringAfter("base64,")
    val base64 = property.types.any { it == "B" || it == "BASE64" } ||
        property.value.startsWith("data:")
    if (!base64 || encoded.length > MAX_PHOTO_BYTES * 4 / 3 + 4) return null
    return runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
}
