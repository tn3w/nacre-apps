package org.nacre.messages

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.core.content.contentValuesOf
import java.io.File
import java.util.UUID

private const val PROVIDER = "org.nacre.messages.files"
private const val MMS_SIZE_FALLBACK = 300 * 1024
private const val MMS_VERSION = 0x12
const val UTF_8 = 106
const val STATE_DOWNLOADING = 0x81
const val STATE_FAILED = 0x87

data class Sim(
    val id: Int,
    val label: String,
)

fun Context.sims(): List<Sim> {
    val manager = getSystemService(SubscriptionManager::class.java)
    val list = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
    return list.map {
        Sim(
            it.subscriptionId,
            it.displayName.clean().ifEmpty { "SIM ${it.simSlotIndex + 1}" },
        )
    }
}

fun defaultSim() = SubscriptionManager.getDefaultSmsSubscriptionId()

private fun Context.smsManager(subscription: Int): SmsManager {
    val manager = getSystemService(SmsManager::class.java)
    return if (subscription >=
        0
    ) {
        manager.createForSubscriptionId(subscription)
    } else {
        manager
    }
}

fun Context.ownNumbers(): List<String> {
    val manager = getSystemService(SubscriptionManager::class.java)
    return sims().mapNotNull { sim ->
        runCatching {
            manager.getPhoneNumber(
                sim.id,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

fun Context.mmsSizeLimit(subscription: Int): Int =
    runCatching {
        smsManager(subscription).carrierConfigValues.getInt(
            SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE,
            MMS_SIZE_FALLBACK,
        )
    }.getOrNull()?.takeIf { it > 0 } ?: MMS_SIZE_FALLBACK

fun smsLength(text: String): IntArray = SmsMessage.calculateLength(text, false)

fun Context.send(
    addresses: List<String>,
    text: String,
    images: List<Part>,
    subscription: Int,
): Long? {
    val recipients = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (recipients.isEmpty() || (text.isBlank() && images.isEmpty())) return null
    val thread = threadFor(recipients)
    if (recipients.size == 1 && images.isEmpty()) {
        sendSms(thread, recipients.first(), text, subscription)
        return thread
    }
    val parts = images + listOfNotNull(text.takeIf { it.isNotBlank() }?.let(::textPart))
    val uri =
        persistMms(
            thread = thread,
            from = null,
            to = recipients,
            parts = parts,
            box = Mms.MESSAGE_BOX_OUTBOX,
            subscription = subscription,
        ) ?: return thread
    transmitMms(uri, recipients, parts, subscription)
    return thread
}

private fun textPart(text: String) = Part("text/plain", "text.txt", text.toByteArray())

private fun Context.sendSms(
    thread: Long,
    address: String,
    text: String,
    subscription: Int,
) {
    val values =
        contentValuesOf(
            Sms.THREAD_ID to thread,
            Sms.ADDRESS to address,
            Sms.BODY to text,
            Sms.DATE to System.currentTimeMillis(),
            Sms.READ to 1,
            Sms.SEEN to 1,
            Sms.TYPE to Sms.MESSAGE_TYPE_OUTBOX,
            Sms.SUBSCRIPTION_ID to subscription,
        )
    val uri = contentResolver.insert(Sms.CONTENT_URI, values) ?: return
    transmitSms(uri, address, text, subscription)
}

private fun Context.statusIntent(
    action: String,
    uri: Uri,
    mutable: Boolean = false,
): PendingIntent {
    val intent = Intent(action, uri, this, StatusReceiver::class.java)
    val flags = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
        this,
        0,
        intent,
        flags or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private fun Context.transmitSms(
    uri: Uri,
    address: String,
    text: String,
    subscription: Int,
) {
    val sent = statusIntent(StatusReceiver.SMS_SENT, uri)
    val delivered =
        statusIntent(StatusReceiver.SMS_DELIVERED, uri, mutable = true)
            .takeIf { Store.deliveryReports.value }
    runCatching {
        val manager = smsManager(subscription)
        val parts = manager.divideMessage(text)
        manager.sendMultipartTextMessage(
            address,
            null,
            parts,
            ArrayList(parts.map { sent }),
            ArrayList(parts.map { delivered }),
        )
    }.onFailure { setSmsBox(uri, Sms.MESSAGE_TYPE_FAILED) }
}

fun Context.setSmsBox(
    uri: Uri,
    type: Int,
) = runCatching {
    contentResolver.update(uri, contentValuesOf(Sms.TYPE to type), null, null)
}

fun Context.setSmsStatus(
    uri: Uri,
    status: Int,
) = runCatching {
    contentResolver.update(uri, contentValuesOf(Sms.STATUS to status), null, null)
}

fun Context.setMmsBox(
    uri: Uri,
    box: Int,
) = runCatching {
    contentResolver.update(uri, contentValuesOf(Mms.MESSAGE_BOX to box), null, null)
}

fun Context.retry(message: Message) {
    if (!message.mms) {
        setSmsBox(message.uri, Sms.MESSAGE_TYPE_OUTBOX)
        val raw = rawSmsBody(message.uri) ?: return
        transmitSms(message.uri, message.address, raw, message.subscription)
        return
    }
    val recipients = mmsRecipients(message.id)
    val parts = storedParts(message.id)
    if (recipients.isEmpty() || parts.isEmpty()) return
    setMmsBox(message.uri, Mms.MESSAGE_BOX_OUTBOX)
    transmitMms(message.uri, recipients, parts, message.subscription)
}

private fun Context.rawSmsBody(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(Sms.BODY), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

private fun Context.mmsRecipients(id: Long): List<String> =
    contentResolver
        .query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf(Mms.Addr.ADDRESS),
            "${Mms.Addr.TYPE} = $ADDRESS_TO",
            null,
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()

private fun Context.storedParts(id: Long): List<Part> =
    contentResolver
        .query(
            Uri.parse("content://mms/part"),
            arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.NAME, Mms.Part.TEXT),
            "${Mms.Part.MSG_ID} = ?",
            arrayOf(id.toString()),
            "${Mms.Part.SEQ} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val type = cursor.getString(1).orEmpty()
                    val name = cursor.getString(2) ?: "part$size"
                    if (type == "text/plain") {
                        add(textPart(cursor.getString(3).orEmpty()))
                        continue
                    }
                    val uri = Uri.parse("content://mms/part/${cursor.getLong(0)}")
                    val bytes =
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: continue
                    add(Part(type, name, bytes))
                }
            }
        }.orEmpty()

private fun Context.pduFile(name: String): File =
    File(cacheDir, "mms").apply { mkdirs() }.resolve(name)

private fun Context.shareWithMmsService(file: File): Uri {
    val uri = FileProvider.getUriForFile(this, PROVIDER, file)
    val flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    listOf("com.android.phone", "com.android.mms.service").forEach {
        runCatching { grantUriPermission(it, uri, flags) }
    }
    return uri
}

fun Context.transmitMms(
    uri: Uri,
    recipients: List<String>,
    parts: List<Part>,
    subscription: Int,
) {
    val file = pduFile("send-${uri.lastPathSegment}.pdu")
    runCatching {
        file.writeBytes(encodeSendRequest(transactionId(), recipients, parts))
        smsManager(subscription).sendMultimediaMessage(
            this,
            shareWithMmsService(file),
            null,
            null,
            statusIntent(StatusReceiver.MMS_SENT, uri),
        )
    }.onFailure {
        file.delete()
        setMmsBox(uri, Mms.MESSAGE_BOX_FAILED)
    }
}

fun Context.finishMmsSend(uri: Uri) {
    pduFile("send-${uri.lastPathSegment}.pdu").delete()
}

private fun transactionId() =
    UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .take(24)

fun Context.download(
    uri: Uri,
    location: String,
    subscription: Int,
) {
    if (!location.startsWith("http://") && !location.startsWith("https://")) return
    val values = contentValuesOf(Mms.STATUS to STATE_DOWNLOADING)
    runCatching { contentResolver.update(uri, values, null, null) }
    val file = pduFile("download-${uri.lastPathSegment}.pdu").apply { delete() }
    runCatching {
        file.createNewFile()
        smsManager(subscription).downloadMultimediaMessage(
            this,
            location,
            shareWithMmsService(file),
            null,
            statusIntent(StatusReceiver.MMS_DOWNLOADED, uri),
        )
    }.onFailure { downloadFailed(uri) }
}

fun Context.downloadFailed(uri: Uri) {
    pduFile("download-${uri.lastPathSegment}.pdu").delete()
    val values = contentValuesOf(Mms.STATUS to STATE_FAILED)
    runCatching { contentResolver.update(uri, values, null, null) }
}

fun Context.readDownload(uri: Uri): ByteArray? {
    val file = pduFile("download-${uri.lastPathSegment}.pdu")
    return try {
        if (file.length() !in 1..MAX_PDU_BYTES) null else file.readBytes()
    } finally {
        file.delete()
    }
}

fun Context.acknowledge(
    transactionId: String,
    subscription: Int,
) {
    if (transactionId.isEmpty()) return
    val file = pduFile("ack-${UUID.randomUUID()}.pdu")
    runCatching {
        file.writeBytes(encodeNotifyResponse(transactionId))
        val intent = statusIntent(StatusReceiver.MMS_ACKNOWLEDGED, Uri.fromFile(file))
        smsManager(subscription)
            .sendMultimediaMessage(this, shareWithMmsService(file), null, null, intent)
    }.onFailure { file.delete() }
}

fun Context.persistMms(
    thread: Long,
    from: String?,
    to: List<String>,
    parts: List<Part>,
    box: Int,
    subscription: Int,
    type: Int = TYPE_SEND_REQ,
    date: Long = System.currentTimeMillis() / 1000,
    extra: ContentValues = ContentValues(),
): Uri? {
    val values =
        contentValuesOf(
            Mms.THREAD_ID to thread,
            Mms.DATE to date,
            Mms.DATE_SENT to date,
            Mms.MESSAGE_BOX to box,
            Mms.READ to if (box == Mms.MESSAGE_BOX_INBOX) 0 else 1,
            Mms.SEEN to if (box == Mms.MESSAGE_BOX_INBOX) 0 else 1,
            Mms.MESSAGE_TYPE to type,
            Mms.MMS_VERSION to MMS_VERSION,
            Mms.CONTENT_TYPE to "application/vnd.wap.multipart.related",
            Mms.SUBSCRIPTION_ID to subscription,
            Mms.TEXT_ONLY to if (parts.all { it.isText }) 1 else 0,
            Mms.MESSAGE_SIZE to parts.sumOf { it.data.size },
        ).apply { putAll(extra) }
    val uri = contentResolver.insert(Mms.CONTENT_URI, values) ?: return null
    val id = uri.lastPathSegment ?: return null
    runCatching {
        parts.forEachIndexed { index, part -> insertPart(id, index, part) }
        from?.let { insertAddress(id, it, ADDRESS_FROM) }
        to.forEach { insertAddress(id, it, ADDRESS_TO) }
    }.onFailure {
        contentResolver.delete(uri, null, null)
        return null
    }
    return uri
}

private fun Context.insertPart(
    id: String,
    index: Int,
    part: Part,
) {
    val name = part.name.ifEmpty { "part$index" }
    val values =
        contentValuesOf(
            Mms.Part.MSG_ID to id,
            Mms.Part.SEQ to index,
            Mms.Part.CONTENT_TYPE to part.type,
            Mms.Part.NAME to name,
            Mms.Part.CONTENT_LOCATION to name,
            Mms.Part.CONTENT_ID to "<$name>",
        )
    if (part.isText) {
        values.put(Mms.Part.CHARSET, UTF_8)
        values.put(Mms.Part.TEXT, part.text)
    }
    val uri =
        contentResolver.insert(Uri.parse("content://mms/$id/part"), values)
            ?: error("part")
    if (part.isText) return
    contentResolver.openOutputStream(uri)?.use { it.write(part.data) }
        ?: error("part data")
}

private fun Context.insertAddress(
    id: String,
    address: String,
    type: Int,
) {
    val values =
        contentValuesOf(
            Mms.Addr.ADDRESS to address,
            Mms.Addr.TYPE to type,
            Mms.Addr.CHARSET to UTF_8,
        )
    contentResolver.insert(Uri.parse("content://mms/$id/addr"), values)
}
