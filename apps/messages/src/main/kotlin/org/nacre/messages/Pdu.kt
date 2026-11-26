package org.nacre.messages

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

const val MAX_PDU_BYTES = 4 * 1024 * 1024
private const val MAX_PARTS = 32
private const val MAX_TEXT_BYTES = 4096
private const val MAX_ADDRESSES = 64
private const val MAX_ADDRESS = 128

const val TYPE_SEND_REQ = 0x80
const val TYPE_NOTIFICATION_IND = 0x82
const val TYPE_NOTIFY_RESP_IND = 0x83
const val TYPE_RETRIEVE_CONF = 0x84

private const val BCC = 0x81
private const val CC = 0x82
private const val CONTENT_LOCATION = 0x83
private const val CONTENT_TYPE = 0x84
private const val DATE = 0x85
private const val EXPIRY = 0x88
private const val FROM = 0x89
private const val MESSAGE_ID = 0x8B
private const val MESSAGE_TYPE = 0x8C
private const val VERSION = 0x8D
private const val MESSAGE_SIZE = 0x8E
private const val STATUS = 0x95
private const val SUBJECT = 0x96
private const val TO = 0x97
private const val TRANSACTION_ID = 0x98

private const val VERSION_1_2 = 0x92
private const val STATUS_RETRIEVED = 0x81
private const val INSERT_ADDRESS = 0x81
private const val CHARSET = 0x81
private const val NAME = 0x85
private const val NAME_TEXT = 0x97
private const val MULTIPART_MIXED = 0x23
private const val CONTENT_ID = 0xC0
private const val PART_LOCATION = 0x8E

private val MEDIA_TYPES =
    mapOf(
        0x03 to "text/plain",
        0x07 to "text/x-vcard",
        0x0C to "multipart/mixed",
        0x1D to "image/gif",
        0x1E to "image/jpeg",
        0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp",
        0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative",
        0x33 to "application/vnd.wap.multipart.related",
    )

private val CHARSETS =
    mapOf(
        3 to Charsets.US_ASCII,
        4 to Charsets.ISO_8859_1,
        106 to Charsets.UTF_8,
        1000 to Charsets.UTF_16BE,
        1013 to Charsets.UTF_16BE,
        1014 to Charsets.UTF_16LE,
        1015 to Charsets.UTF_16,
    )

class PduException : Exception("Malformed MMS PDU")

class Part(
    val type: String,
    val name: String,
    val data: ByteArray,
    val charset: Charset = Charsets.UTF_8,
) {
    val isText get() = type == "text/plain"

    val text get() = data.toString(charset)
}

private class ContentType(
    val media: String,
    val name: String = "",
    val charset: Charset = Charsets.UTF_8,
)

data class Pdu(
    val type: Int,
    val transactionId: String = "",
    val from: String = "",
    val to: List<String> = emptyList(),
    val subject: String = "",
    val location: String = "",
    val size: Long = 0,
    val expiry: Long = 0,
    val date: Long = 0,
    val messageId: String = "",
    val parts: List<Part> = emptyList(),
)

private class Reader(
    private val bytes: ByteArray,
    var position: Int = 0,
    private val end: Int = bytes.size,
) {
    val remaining get() = end - position

    fun peek(): Int {
        if (position >= end) throw PduException()
        return bytes[position].toInt() and 0xFF
    }

    fun byte() = peek().also { position++ }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || count > remaining) throw PduException()
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun skip(count: Int) {
        if (count < 0 || count > remaining) throw PduException()
        position += count
    }

    fun sub(length: Int): Reader {
        if (length < 0 || length > remaining) throw PduException()
        return Reader(bytes, position, position + length).also { position += length }
    }

    fun uintvar(): Int {
        var value = 0L
        repeat(5) {
            val octet = byte()
            value = (value shl 7) or (octet and 0x7F).toLong()
            if (value > Int.MAX_VALUE) throw PduException()
            if (octet and 0x80 == 0) return value.toInt()
        }
        throw PduException()
    }

    fun valueLength(): Int {
        val first = byte()
        return when {
            first < 31 -> first
            first == 31 -> uintvar()
            else -> throw PduException()
        }
    }

    fun longInteger(): Long {
        val length = byte()
        if (length !in 1..8) throw PduException()
        var value = 0L
        repeat(length) { value = (value shl 8) or byte().toLong() }
        return value
    }

    fun integer(): Long =
        if (peek() >=
            0x80
        ) {
            (byte() and 0x7F).toLong()
        } else {
            longInteger()
        }

    fun rawText(): ByteArray {
        if (peek() == 0x7F) position++
        val start = position
        while (position < end && bytes[position] != 0.toByte()) {
            if (position - start >= MAX_TEXT_BYTES) throw PduException()
            position++
        }
        if (position >= end) throw PduException()
        return bytes.copyOfRange(start, position).also { position++ }
    }

    fun text() = rawText().toString(Charsets.UTF_8)

    fun encodedText(): String {
        if (peek() >= 32) return text()
        val value = sub(valueLength())
        val charset = CHARSETS[value.integer().toInt()] ?: Charsets.UTF_8
        return value.rawText().toString(charset)
    }

    fun skipValue() {
        val first = peek()
        when {
            first < 31 -> skip(byte())
            first == 31 -> skip(valueLength())
            first < 128 -> rawText()
            else -> position++
        }
    }
}

fun parsePdu(bytes: ByteArray): Pdu {
    if (bytes.size > MAX_PDU_BYTES) throw PduException()
    return try {
        Reader(bytes).pdu()
    } catch (error: PduException) {
        throw error
    } catch (_: RuntimeException) {
        throw PduException()
    }
}

private fun Reader.pdu(): Pdu {
    var pdu = Pdu(type = 0)
    var addresses = 0
    while (remaining > 0) {
        val field = byte()
        if (field < 0x80) {
            position--
            rawText()
            skipValue()
            continue
        }
        if (field == CONTENT_TYPE) {
            val type = contentType().media
            val parts = if (type.contains("multipart")) multipart() else emptyList()
            return pdu.copy(parts = parts)
        }
        if (field in listOf(TO, CC, BCC, FROM) && ++addresses > MAX_ADDRESSES) {
            throw PduException()
        }
        pdu =
            when (field) {
                MESSAGE_TYPE -> pdu.copy(type = byte())
                TRANSACTION_ID -> pdu.copy(transactionId = text())
                FROM -> pdu.copy(from = from())
                TO, CC -> pdu.copy(to = pdu.to + address(encodedText()))
                SUBJECT -> pdu.copy(subject = encodedText())
                CONTENT_LOCATION -> pdu.copy(location = text())
                MESSAGE_SIZE -> pdu.copy(size = longInteger().coerceAtLeast(0))
                EXPIRY -> pdu.copy(expiry = expiry())
                DATE -> pdu.copy(date = longInteger())
                MESSAGE_ID -> pdu.copy(messageId = text())
                else -> pdu.also { skipValue() }
            }
    }
    return pdu
}

private fun Reader.from(): String {
    val value = sub(valueLength())
    if (value.byte() != 0x80) return ""
    return address(value.encodedText())
}

private fun Reader.expiry(): Long {
    val value = sub(valueLength())
    val token = value.byte()
    val time = value.integer()
    return if (token == 0x80) time else System.currentTimeMillis() / 1000 + time
}

private fun address(raw: String) = raw.substringBefore("/TYPE=").trim().take(MAX_ADDRESS)

private fun Reader.contentType(): ContentType {
    val first = peek()
    if (first >= 0x80) return ContentType(MEDIA_TYPES[byte() and 0x7F].orEmpty())
    if (first >= 32) return ContentType(text().lowercase())
    val value = sub(valueLength())
    val media =
        if (value.peek() >= 0x80) {
            MEDIA_TYPES[value.byte() and 0x7F] ?: ""
        } else if (value.peek() < 32) {
            MEDIA_TYPES[value.integer().toInt()] ?: ""
        } else {
            value.text().lowercase()
        }
    var name = ""
    var charset = Charsets.UTF_8
    runCatching {
        while (value.remaining > 0) {
            when (value.byte()) {
                NAME, NAME_TEXT -> name = value.text()
                CHARSET -> charset = CHARSETS[value.integer().toInt()] ?: charset
                else -> value.skipValue()
            }
        }
    }
    return ContentType(media, name.take(MAX_TEXT_BYTES), charset)
}

private fun Reader.multipart(): List<Part> {
    val count = uintvar()
    if (count > MAX_PARTS) throw PduException()
    return List(count) {
        val headersLength = uintvar()
        val dataLength = uintvar()
        val headers = sub(headersLength)
        val type = headers.contentType()
        Part(type.media, type.name, bytes(dataLength), type.charset)
    }.filterNot { it.type.contains("multipart") }
}

private class Writer {
    val out = ByteArrayOutputStream()

    fun byte(value: Int) = out.write(value)

    fun bytes(value: ByteArray) = out.write(value)

    fun text(value: String) {
        val bytes = value.filter { it >= ' ' }.toByteArray(Charsets.UTF_8)
        if (bytes.firstOrNull()?.let { it.toInt() and 0xFF >= 0x80 } == true) byte(0x7F)
        bytes(bytes)
        byte(0)
    }

    fun uintvar(value: Int) {
        val groups = mutableListOf(value and 0x7F)
        var rest = value ushr 7
        while (rest > 0) {
            groups += (rest and 0x7F) or 0x80
            rest = rest ushr 7
        }
        groups.reversed().forEach(::byte)
    }

    fun valueLength(length: Int) {
        if (length < 31) return byte(length)
        byte(31)
        uintvar(length)
    }

    fun block(content: Writer.() -> Unit) {
        val bytes = Writer().apply(content).out.toByteArray()
        valueLength(bytes.size)
        bytes(bytes)
    }
}

fun encodeSendRequest(
    transactionId: String,
    recipients: List<String>,
    parts: List<Part>,
): ByteArray {
    val writer = Writer()
    with(writer) {
        byte(MESSAGE_TYPE)
        byte(TYPE_SEND_REQ)
        byte(TRANSACTION_ID)
        text(transactionId)
        byte(VERSION)
        byte(VERSION_1_2)
        byte(FROM)
        byte(1)
        byte(INSERT_ADDRESS)
        recipients.forEach {
            byte(TO)
            text(if (it.contains('@')) it else "$it/TYPE=PLMN")
        }
        byte(CONTENT_TYPE)
        byte(MULTIPART_MIXED or 0x80)
        uintvar(parts.size)
        parts.forEach { part(it) }
    }
    return writer.out.toByteArray()
}

private fun Writer.part(part: Part) {
    val headers =
        Writer()
            .apply {
                block {
                    text(part.type)
                    if (part.isText) {
                        byte(CHARSET)
                        byte(UTF_8 or 0x80)
                    }
                    byte(NAME)
                    text(part.name)
                }
                byte(PART_LOCATION)
                text(part.name)
                byte(CONTENT_ID)
                text("<${part.name}>")
            }.out
            .toByteArray()
    uintvar(headers.size)
    uintvar(part.data.size)
    bytes(headers)
    bytes(part.data)
}

fun encodeNotifyResponse(transactionId: String): ByteArray =
    Writer()
        .apply {
            byte(MESSAGE_TYPE)
            byte(TYPE_NOTIFY_RESP_IND)
            byte(TRANSACTION_ID)
            text(transactionId)
            byte(VERSION)
            byte(VERSION_1_2)
            byte(STATUS)
            byte(STATUS_RETRIEVED)
        }.out
        .toByteArray()
