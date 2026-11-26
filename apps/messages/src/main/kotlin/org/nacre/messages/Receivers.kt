package org.nacre.messages

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import android.app.Person as NotificationPerson

const val MESSAGES_CHANNEL = "messages"
private const val NOTIFIED_MESSAGES = 8
private const val NOTIFIED_CHARS = 500
private const val REPLY_KEY = "reply"
private val CODE = Regex("""(?<![\d.,])(\d{4,8})(?![\d.,])""")
private val CODE_WORDS =
    Regex("(?i)code|otp|pin|passcode|password|verif|token|login|einmal|código|codice")

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

private fun Intent.subscription() =
    getIntExtra(
        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
        SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    )

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = runCatching { Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
        val first = parts?.firstOrNull() ?: return
        val address = first.displayOriginatingAddress.orEmpty().take(64)
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val subscription = intent.subscription()
        work {
            if (context.isBlocked(address)) return@work
            val values =
                contentValuesOf(
                    Sms.ADDRESS to address,
                    Sms.BODY to body,
                    Sms.DATE to System.currentTimeMillis(),
                    Sms.DATE_SENT to first.timestampMillis,
                    Sms.READ to 0,
                    Sms.SEEN to 0,
                    Sms.TYPE to Sms.MESSAGE_TYPE_INBOX,
                    Sms.SUBSCRIPTION_ID to subscription,
                    Sms.PROTOCOL to first.protocolIdentifier,
                    Sms.REPLY_PATH_PRESENT to if (first.isReplyPathPresent) 1 else 0,
                    Sms.SERVICE_CENTER to first.serviceCenterAddress,
                )
            val uri =
                context.contentResolver.insert(Sms.Inbox.CONTENT_URI, values)
                    ?: return@work
            context.message(uri)?.let { context.notifyThread(it.thread) }
        }
    }
}

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        if (intent.type != "application/vnd.wap.mms-message") return
        val data = intent.getByteArrayExtra("data") ?: return
        val subscription = intent.subscription()
        work { context.receiveNotification(data, subscription) }
    }
}

private fun Context.receiveNotification(
    data: ByteArray,
    subscription: Int,
) {
    val pdu = runCatching { parsePdu(data) }.getOrNull() ?: return
    if (pdu.type != TYPE_NOTIFICATION_IND) return
    if (pdu.location.isEmpty() || pdu.from.isEmpty()) return
    if (isBlocked(pdu.from) || isDuplicate(pdu.location)) return
    val thread = threadFor(listOf(pdu.from))
    val extra =
        contentValuesOf(
            Mms.CONTENT_LOCATION to pdu.location.take(1024),
            Mms.EXPIRY to pdu.expiry,
            Mms.TRANSACTION_ID to pdu.transactionId.take(256),
            Mms.SUBJECT to pdu.subject.take(256),
            Mms.SUBJECT_CHARSET to UTF_8,
        )
    val uri =
        persistMms(
            thread = thread,
            from = pdu.from,
            to = emptyList(),
            parts = emptyList(),
            box = Mms.MESSAGE_BOX_INBOX,
            subscription = subscription,
            type = TYPE_NOTIFICATION_IND,
            extra = extra,
        ) ?: return
    contentResolver.update(uri, contentValuesOf(Mms.MESSAGE_SIZE to pdu.size), null, null)
    val known = People(this)[pdu.from].known
    val automatic =
        when (Store.autoDownload.value) {
            AutoDownload.Always -> true
            AutoDownload.Contacts -> known
            AutoDownload.Never -> false
        }
    if (automatic) download(uri, pdu.location, subscription)
    notifyThread(thread)
}

private fun Context.isDuplicate(location: String) =
    contentResolver
        .query(
            Mms.CONTENT_URI,
            arrayOf(Mms._ID),
            "${Mms.CONTENT_LOCATION} = ?",
            arrayOf(location),
            null,
        )?.use { it.count > 0 } == true

private fun Context.receiveDownload(uri: Uri) {
    val notification = message(uri) ?: return
    val bytes = readDownload(uri)
    val pdu = bytes?.let { runCatching { parsePdu(it) }.getOrNull() }
    if (pdu == null || pdu.type != TYPE_RETRIEVE_CONF) return downloadFailed(uri)
    val from = pdu.from.ifEmpty { notification.address }
    if (isBlocked(from)) {
        contentResolver.delete(uri, null, null)
        return
    }
    val own = ownNumbers()
    val others = pdu.to.filterNot { address -> own.any { sameAddress(it, address) } }
    val group = if (own.isEmpty() && pdu.to.size <= 1) emptyList() else others
    val recipients = (listOf(from) + group).distinctBy { it.lowercase() }
    val thread = threadFor(recipients)
    val parts = pdu.parts.filter { it.type.isNotEmpty() && it.type != "application/smil" }
    val extra =
        contentValuesOf(
            Mms.SUBJECT to pdu.subject.take(256),
            Mms.SUBJECT_CHARSET to UTF_8,
            Mms.MESSAGE_ID to pdu.messageId.take(256),
            Mms.TRANSACTION_ID to pdu.transactionId.take(256),
        )
    val date = pdu.date.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
    persistMms(
        thread = thread,
        from = from,
        to = pdu.to,
        parts = parts,
        box = Mms.MESSAGE_BOX_INBOX,
        subscription = notification.subscription,
        type = TYPE_RETRIEVE_CONF,
        date = date,
        extra = extra,
    ) ?: return downloadFailed(uri)
    contentResolver.delete(uri, null, null)
    acknowledge(pdu.transactionId, notification.subscription)
    notifyThread(thread)
}

class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val uri = intent.data ?: return
        val ok = resultCode == Activity.RESULT_OK
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        work {
            when (intent.action) {
                SMS_SENT -> {
                    context.smsSent(uri, ok)
                }

                SMS_DELIVERED -> {
                    context.smsDelivered(uri, pdu, format)
                }

                MMS_SENT -> {
                    context.mmsSent(uri, ok)
                }

                MMS_DOWNLOADED -> {
                    if (ok) context.receiveDownload(uri) else context.downloadFailed(uri)
                }

                MMS_ACKNOWLEDGED -> {
                    uri.path?.let { context.releasePdu(File(it)) }
                }
            }
        }
    }

    companion object {
        const val SMS_SENT = "org.nacre.messages.SMS_SENT"
        const val SMS_DELIVERED = "org.nacre.messages.SMS_DELIVERED"
        const val MMS_SENT = "org.nacre.messages.MMS_SENT"
        const val MMS_DOWNLOADED = "org.nacre.messages.MMS_DOWNLOADED"
        const val MMS_ACKNOWLEDGED = "org.nacre.messages.MMS_ACKNOWLEDGED"
    }
}

private fun Context.smsSent(
    uri: Uri,
    ok: Boolean,
) {
    val message = message(uri) ?: return
    if (!ok) {
        setSmsBox(uri, Sms.MESSAGE_TYPE_FAILED)
        notifyFailed(message.thread)
        return
    }
    if (message.box == Box.Outbox) setSmsBox(uri, Sms.MESSAGE_TYPE_SENT)
}

private fun Context.smsDelivered(
    uri: Uri,
    pdu: ByteArray?,
    format: String?,
) {
    val status =
        runCatching { SmsMessage.createFromPdu(pdu, format).status }.getOrNull() ?: return
    setSmsStatus(
        uri,
        when {
            status >= Sms.STATUS_FAILED -> Sms.STATUS_FAILED
            status >= Sms.STATUS_PENDING -> Sms.STATUS_PENDING
            else -> Sms.STATUS_COMPLETE
        },
    )
}

private fun Context.mmsSent(
    uri: Uri,
    ok: Boolean,
) {
    finishMmsSend(uri)
    setMmsBox(uri, if (ok) Mms.MESSAGE_BOX_SENT else Mms.MESSAGE_BOX_FAILED)
    if (!ok) message(uri)?.let { notifyFailed(it.thread) }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val thread = intent.data?.schemeSpecificPart?.toLongOrNull() ?: return
        val reply =
            RemoteInput
                .getResultsFromIntent(
                    intent,
                )?.getCharSequence(REPLY_KEY)
                ?.toString()
        val code = intent.getStringExtra(EXTRA_CODE)
        work {
            when (intent.action) {
                REPLY -> context.reply(thread, reply)
                MARK_READ -> context.markThreadRead(thread)
                COPY_CODE -> code?.let(context::copySensitive)
            }
        }
    }

    companion object {
        const val REPLY = "org.nacre.messages.REPLY"
        const val MARK_READ = "org.nacre.messages.MARK_READ"
        const val COPY_CODE = "org.nacre.messages.COPY_CODE"
        const val EXTRA_CODE = "code"
    }
}

private fun Context.reply(
    thread: Long,
    text: String?,
) {
    if (text.isNullOrBlank()) return
    val conversation = conversation(thread) ?: return
    val subscription =
        messages(thread).lastOrNull { it.incoming }?.subscription ?: defaultSim()
    send(conversation.addresses, text, emptyList(), subscription)
    markThreadRead(thread)
}

fun Context.markThreadRead(thread: Long) {
    contentResolver.markRead(thread)
    getSystemService(NotificationManager::class.java).cancel(thread.toInt())
}

fun Context.copySensitive(text: String) {
    val clip = ClipData.newPlainText("Code", text)
    clip.description.extras =
        PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

class RespondService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val addresses = intent?.data?.let(::recipients).orEmpty()
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE ||
            text.isNullOrBlank()
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            send(addresses, text, emptyList(), defaultSim())
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

fun recipients(uri: Uri): List<String> {
    if (uri.scheme !in listOf("sms", "smsto", "mms", "mmsto")) return emptyList()
    return uri.schemeSpecificPart
        .orEmpty()
        .substringBefore('?')
        .split(',', ';')
        .map { it.trim().clean() }
        .filter { it.isNotEmpty() }
        .take(MAX_RECIPIENTS)
}

const val MAX_RECIPIENTS = 20

private fun Context.threadIntent(
    action: String,
    thread: Long,
    mutable: Boolean = false,
    code: String? = null,
): PendingIntent {
    val intent =
        Intent(
            action,
            Uri.fromParts("thread", thread.toString(), null),
            this,
            ActionReceiver::class.java,
        ).putExtra(ActionReceiver.EXTRA_CODE, code)
    val flags = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
        this,
        0,
        intent,
        flags or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

fun Context.openThreadIntent(thread: Long): PendingIntent {
    val intent =
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.fromParts("thread", thread.toString(), null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
}

fun messagePreview(message: Message): String =
    message.body
        .ifEmpty {
            when {
                message.pending != null -> "Multimedia message"
                message.attachments.any { it.isImage } -> "Photo"
                message.attachments.isNotEmpty() -> "Attachment"
                else -> ""
            }
        }.take(NOTIFIED_CHARS)

fun findCode(text: String): String? {
    if (!CODE_WORDS.containsMatchIn(text)) return null
    return CODE.findAll(text).map { it.value }.singleOrNull()
}

fun Context.notifyThread(thread: Long) {
    val manager = getSystemService(NotificationManager::class.java)
    if (thread in Store.muted.value || thread == Visible.thread) return
    val conversation = conversation(thread) ?: return
    val unread = unread(thread).takeLast(NOTIFIED_MESSAGES)
    if (unread.isEmpty()) return manager.cancel(thread.toInt())
    if (!Store.notifyUnknown.value && conversation.people.none { it.known }) return
    val people = People(this)
    val me = NotificationPerson.Builder().setName("You").build()
    val style =
        Notification
            .MessagingStyle(me)
            .setGroupConversation(conversation.people.size > 1)
            .setConversationTitle(
                conversation.title.takeIf { conversation.people.size > 1 },
            )
    unread.forEach { message ->
        val sender = people[message.address.ifEmpty { conversation.addresses.first() }]
        val person =
            NotificationPerson
                .Builder()
                .setName(
                    sender.name,
                ).setKey(sender.address)
                .build()
        style.addMessage(messagePreview(message), message.date, person)
    }
    val reply =
        Notification.Action
            .Builder(
                null,
                "Reply",
                threadIntent(ActionReceiver.REPLY, thread, mutable = true),
            ).addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()
    val read =
        Notification.Action
            .Builder(null, "Mark as read", threadIntent(ActionReceiver.MARK_READ, thread))
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    val code = findCode(unread.last().body)
    val copy =
        code?.let {
            Notification.Action
                .Builder(
                    null,
                    "Copy $it",
                    threadIntent(ActionReceiver.COPY_CODE, thread, code = it),
                ).setAuthenticationRequired(true)
                .build()
        }
    val public =
        Notification
            .Builder(this, MESSAGES_CHANNEL)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle("New message")
            .build()
    val notification =
        Notification
            .Builder(this, MESSAGES_CHANNEL)
            .setSmallIcon(R.drawable.ic_message)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openThreadIntent(thread))
            .setAllowSystemGeneratedContextualActions(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(unread.last().date)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setActions(*listOfNotNull(copy, reply, read).toTypedArray())
            .build()
    runCatching { manager.notify(thread.toInt(), notification) }
}

private fun Context.notifyFailed(thread: Long) {
    val notification =
        Notification
            .Builder(this, MESSAGES_CHANNEL)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle("Message not sent")
            .setContentText("Tap to open the conversation and try again")
            .setContentIntent(openThreadIntent(thread))
            .setAutoCancel(true)
            .build()
    runCatching {
        getSystemService(
            NotificationManager::class.java,
        ).notify(-thread.toInt(), notification)
    }
}

object Visible {
    @Volatile
    var thread: Long? = null
}
