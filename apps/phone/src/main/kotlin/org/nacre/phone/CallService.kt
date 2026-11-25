package org.nacre.phone

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
import android.os.OutcomeReceiver
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow

private const val INCOMING_CHANNEL = "incoming"
private const val ONGOING_CHANNEL = "ongoing"
private const val NOTIFICATION_ID = 1

class PhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(
                    INCOMING_CHANNEL,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) },
                NotificationChannel(
                    ONGOING_CHANNEL,
                    "Ongoing calls",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setSound(null, null) },
            ),
        )
    }
}

object Ongoing {
    val list = MutableStateFlow(emptyList<Call>())
    val version = MutableStateFlow(0)
    val muted = MutableStateFlow(false)
    val endpoint = MutableStateFlow<CallEndpoint?>(null)
    val endpoints = MutableStateFlow(emptyList<CallEndpoint>())
    var service: CallService? = null

    val callback =
        object : Call.Callback() {
            override fun onStateChanged(
                call: Call,
                state: Int,
            ) = changed()

            override fun onDetailsChanged(
                call: Call,
                details: Call.Details,
            ) = changed()

            override fun onChildrenChanged(
                call: Call,
                children: List<Call>,
            ) = changed()

            override fun onConferenceableCallsChanged(
                call: Call,
                calls: List<Call>,
            ) = changed()

            override fun onCannedTextResponsesLoaded(
                call: Call,
                responses: List<String>,
            ) = changed()
        }

    fun changed() {
        version.value++
        service?.update()
    }

    val top get() = list.value.filter { it.parent == null }

    val ringing get() = top.find { it.details.state == Call.STATE_RINGING }

    val primary get() = top.minByOrNull { rank(it.details.state) }

    val background get() = top.filter { it != primary }

    private fun rank(state: Int) =
        when (state) {
            Call.STATE_RINGING -> 0
            Call.STATE_ACTIVE -> 2
            Call.STATE_HOLDING -> 3
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> 4
            else -> 1
        }

    fun answer() = ringing?.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun decline() = ringing?.reject(false, null)

    fun hangUp() = primary?.disconnect()

    fun setMuted(muted: Boolean) = service?.setMuted(muted)

    fun route(endpoint: CallEndpoint) {
        val service = service ?: return
        val receiver =
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit
            }
        service.requestCallEndpointChange(endpoint, service.mainExecutor, receiver)
    }
}

class CallService : InCallService() {
    private var proximity: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        Ongoing.service = this
        proximity =
            getSystemService(PowerManager::class.java)
                .newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "phone:proximity",
                )
    }

    override fun onDestroy() {
        Ongoing.service = null
        proximity?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(Ongoing.callback)
        Ongoing.list.value += call
        Ongoing.changed()
        if (call.details.state != Call.STATE_RINGING || isAway()) showCall()
    }

    private fun isAway() =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(PowerManager::class.java).isInteractive

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(Ongoing.callback)
        Ongoing.list.value -= call
        Ongoing.changed()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        Ongoing.muted.value = isMuted
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        Ongoing.endpoint.value = callEndpoint
        Ongoing.changed()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        Ongoing.endpoints.value = availableEndpoints
    }

    private fun showCall() {
        val intent =
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    fun update() {
        updateProximity()
        val call = Ongoing.primary
        if (call == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            return
        }
        val notification = notification(call)
        if (foreground) {
            getSystemService(
                NotificationManager::class.java,
            ).notify(NOTIFICATION_ID, notification)
            return
        }
        foreground =
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                )
            }.isSuccess
    }

    private fun updateProximity() {
        val lock = proximity ?: return
        val state = Ongoing.primary?.details?.state
        val talking = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING
        val earpiece = Ongoing.endpoint.value?.endpointType == CallEndpoint.TYPE_EARPIECE
        when {
            talking && earpiece && !lock.isHeld -> lock.acquire()
            !(talking && earpiece) && lock.isHeld -> lock.release()
        }
    }

    private fun notification(call: Call): Notification {
        val details = call.details
        val ringing = details.state == Call.STATE_RINGING
        val person =
            Person
                .Builder()
                .setName(callerName(details))
                .setImportant(true)
                .build()
        val open = activityIntent(null)
        val style =
            if (ringing) {
                Notification.CallStyle.forIncomingCall(
                    person,
                    broadcast(CallActionReceiver.DECLINE),
                    activityIntent(InCallActivity.ANSWER),
                )
            } else {
                Notification.CallStyle.forOngoingCall(
                    person,
                    broadcast(CallActionReceiver.HANG_UP),
                )
            }
        val connected =
            details.state == Call.STATE_ACTIVE && details.connectTimeMillis > 0
        return Notification
            .Builder(
                this,
                if (ringing) INCOMING_CHANNEL else ONGOING_CHANNEL,
            ).setSmallIcon(R.drawable.ic_call)
            .setStyle(style)
            .setContentText(statusText(details))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!ringing)
            .setShowWhen(connected)
            .setUsesChronometer(connected)
            .setWhen(
                if (connected) details.connectTimeMillis else System.currentTimeMillis(),
            ).apply { if (ringing) setFullScreenIntent(open, true) }
            .build()
    }

    private fun activityIntent(action: String?): PendingIntent {
        val intent = Intent(this, InCallActivity::class.java).setAction(action)
        return PendingIntent.getActivity(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcast(action: String): PendingIntent {
        val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            DECLINE -> Ongoing.decline()
            HANG_UP -> Ongoing.hangUp()
        }
    }

    companion object {
        const val DECLINE = "org.nacre.phone.DECLINE"
        const val HANG_UP = "org.nacre.phone.HANG_UP"
    }
}

fun Call.Details.number(): String = handle?.schemeSpecificPart.orEmpty()

fun Context.callerName(details: Call.Details): String =
    when {
        details.hasProperty(Call.Details.PROPERTY_CONFERENCE) -> {
            "Conference call"
        }

        details.handlePresentation == TelecomManager.PRESENTATION_RESTRICTED -> {
            "Private number"
        }

        details.handlePresentation == TelecomManager.PRESENTATION_PAYPHONE -> {
            "Payphone"
        }

        else -> {
            listOf(details.contactDisplayName, details.callerDisplayName)
                .firstOrNull { !it.isNullOrBlank() }
                ?: details.number().takeIf { it.isNotEmpty() }?.let(::formatNumber)
                ?: "Unknown"
        }
    }

fun statusText(details: Call.Details): String =
    when (details.state) {
        Call.STATE_RINGING -> {
            "Incoming call"
        }

        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> {
            "Calling…"
        }

        Call.STATE_SELECT_PHONE_ACCOUNT -> {
            "Choose a SIM"
        }

        Call.STATE_HOLDING -> {
            "On hold"
        }

        Call.STATE_ACTIVE -> {
            "Ongoing call"
        }

        Call.STATE_DISCONNECTING -> {
            "Hanging up…"
        }

        Call.STATE_DISCONNECTED -> {
            details.disconnectCause
                ?.label
                ?.toString()
                ?.takeIf { it.isNotBlank() } ?: "Call ended"
        }

        else -> {
            ""
        }
    }
