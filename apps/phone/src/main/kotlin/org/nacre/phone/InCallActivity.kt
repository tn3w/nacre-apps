package org.nacre.phone

import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.nacre.core.NacreTheme

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        handle(intent)
        setContent { NacreTheme { InCallScreen(::finishAndRemoveTask) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action == ANSWER) Ongoing.answer()
        intent.action = null
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val volume =
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!volume || Ongoing.ringing == null) return super.onKeyDown(keyCode, event)
        runCatching { getSystemService(TelecomManager::class.java).silenceRinger() }
        return true
    }

    companion object {
        const val ANSWER = "org.nacre.phone.ANSWER"
    }
}

private fun Call.canMerge() =
    details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) ||
        conferenceableCalls.isNotEmpty()

private fun Call.merge() {
    if (details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
        mergeConference()
        return
    }
    conferenceableCalls.firstOrNull()?.let(::conference)
}

@Composable
fun InCallScreen(onDone: () -> Unit) {
    val version by Ongoing.version.collectAsState()
    val primary = remember(version) { Ongoing.primary }
    var last by remember { mutableStateOf(primary) }
    LaunchedEffect(primary) {
        if (primary != null) {
            last = primary
            return@LaunchedEffect
        }
        delay(1500)
        onDone()
    }
    Surface(Modifier.fillMaxSize()) {
        val call = primary ?: last ?: return@Surface
        val details = remember(version, call) { call.details }
        val ended = primary == null || details.state == Call.STATE_DISCONNECTED
        var keypad by rememberSaveable { mutableStateOf(false) }
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Caller(call, details, ended)
            if (!ended) {
                remember(version) { Ongoing.background }.forEach { Held(it) }
            }
            Spacer(Modifier.weight(1f))
            when {
                ended -> {
                    Unit
                }

                details.state == Call.STATE_RINGING -> {
                    Incoming(call)
                }

                details.state == Call.STATE_SELECT_PHONE_ACCOUNT -> {
                    Accounts(
                        call,
                        details,
                    )
                }

                keypad -> {
                    Keypad(call) { keypad = false }
                }

                else -> {
                    Controls(call, details) { keypad = true }
                }
            }
            if (!ended && details.state != Call.STATE_RINGING) {
                RoundButton(
                    R.drawable.ic_call_end,
                    "End call",
                    Modifier.padding(top = 24.dp),
                ) {
                    call.disconnect()
                }
            }
        }
    }
}

@Composable
private fun Caller(
    call: Call,
    details: Call.Details,
    ended: Boolean,
) {
    val context = LocalContext.current
    val number = details.number()
    val known by produceState<Number?>(null, number) {
        value = withContext(Dispatchers.IO) { context.lookup(number) }
    }
    val name = context.callerName(details)
    val conference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
    val subtitle =
        when {
            conference -> {
                call.children.joinToString { context.callerName(it.details) }
            }

            known != null -> {
                "${known?.label} · ${context.formatNumber(number)}"
            }

            name != context.formatNumber(number) && number.isNotEmpty() -> {
                context.formatNumber(number)
            }

            else -> {
                ""
            }
        }
    val connected = details.state == Call.STATE_ACTIVE && details.connectTimeMillis > 0
    if (connected && !ended) {
        Elapsed(details.connectTimeMillis)
    } else {
        Text(
            if (ended) "Call ended" else statusText(details),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    Spacer(Modifier.height(24.dp))
    Avatar(known?.contact?.name ?: name, known?.contact?.photo, 112.dp)
    Spacer(Modifier.height(16.dp))
    Text(
        name,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (subtitle.isNotEmpty()) {
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Elapsed(since: Long) {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(duration((now - since) / 1000), style = MaterialTheme.typography.titleMedium)
}

fun duration(seconds: Long): String {
    val clamped = seconds.coerceAtLeast(0)
    val hours = clamped / 3600
    val minutes = clamped / 60 % 60
    val rest = clamped % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

@Composable
private fun Held(call: Call) {
    val context = LocalContext.current
    val details = call.details
    Text(
        "${statusText(details)} · ${context.callerName(details)} · Tap to switch",
        Modifier.padding(top = 16.dp).clickable { call.unhold() }.padding(8.dp),
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Incoming(call: Call) {
    var replying by remember { mutableStateOf(false) }
    val responses = call.cannedTextResponses.orEmpty()
    if (responses.isNotEmpty()) {
        TextButton(onClick = { replying = true }) { Text("Reply with message") }
        Spacer(Modifier.height(16.dp))
    }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
        Labeled("Decline") {
            RoundButton(R.drawable.ic_call_end, "Decline", primary = false) {
                call.reject(false, null)
            }
        }
        Labeled("Answer") {
            RoundButton(R.drawable.ic_call, "Answer") { Ongoing.answer() }
        }
    }
    if (!replying) return
    AlertDialog(
        onDismissRequest = { replying = false },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { replying = false }) { Text("Cancel") } },
        title = { Text("Reply with message") },
        text = {
            Column {
                responses.forEach { response ->
                    Text(
                        response,
                        Modifier
                            .fillMaxWidth()
                            .clickable { call.reject(true, response) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun Accounts(
    call: Call,
    details: Call.Details,
) {
    val context = LocalContext.current
    val telecom = context.getSystemService(TelecomManager::class.java)
    val key = Call.AVAILABLE_PHONE_ACCOUNTS
    val type = PhoneAccountHandle::class.java
    val handles =
        details.extras?.getParcelableArrayList(key, type)
            ?: details.intentExtras?.getParcelableArrayList(key, type)
            ?: runCatching { telecom.callCapablePhoneAccounts }.getOrDefault(emptyList())
    Text("Call with", style = MaterialTheme.typography.titleMedium)
    handles.forEach { handle ->
        val label = runCatching { telecom.getPhoneAccount(handle)?.label }.getOrNull()
        TextButton(onClick = { call.phoneAccountSelected(handle, false) }) {
            Text(label?.toString() ?: handle.id)
        }
    }
}

@Composable
private fun Keypad(
    call: Call,
    onHide: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Text(typed, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    Spacer(Modifier.height(8.dp))
    Dialpad(
        onPress = {
            typed += it
            call.playDtmfTone(it)
        },
        onRelease = { call.stopDtmfTone() },
    )
    TextButton(onClick = onHide) { Text("Hide keypad") }
}

@Composable
private fun Controls(
    call: Call,
    details: Call.Details,
    onKeypad: () -> Unit,
) {
    val context = LocalContext.current
    val muted by Ongoing.muted.collectAsState()
    val held = details.state == Call.STATE_HOLDING
    val others = Ongoing.background
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row {
            Control(R.drawable.ic_mic_off, "Mute", muted) { Ongoing.setMuted(!muted) }
            Control(R.drawable.ic_dialpad, "Keypad", onClick = onKeypad)
            Audio()
        }
        Row {
            Control(
                R.drawable.ic_pause,
                if (held) "Resume" else "Hold",
                held,
                details.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
            ) { if (held) call.unhold() else call.hold() }
            Control(R.drawable.ic_add, "Add call") {
                val intent =
                    Intent(context, MainActivity::class.java)
                        .putExtra("tab", Tab.Keypad.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            if (call.canMerge()) {
                Control(R.drawable.ic_merge, "Merge") { call.merge() }
            } else {
                Control(R.drawable.ic_swap, "Swap", enabled = others.isNotEmpty()) {
                    others.firstOrNull()?.unhold()
                }
            }
        }
    }
}

@Composable
private fun Audio() {
    val endpoint by Ongoing.endpoint.collectAsState()
    val endpoints by Ongoing.endpoints.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val type = endpoint?.endpointType
    val speaker = endpoints.find { it.endpointType == CallEndpoint.TYPE_SPEAKER }
    val private = endpoints.find { it.endpointType != CallEndpoint.TYPE_SPEAKER }
    val chooser = endpoints.any { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
    if (!chooser) {
        val on = type == CallEndpoint.TYPE_SPEAKER
        Control(R.drawable.ic_speaker, "Speaker", on, speaker != null) {
            (if (on) private else speaker)?.let(Ongoing::route)
        }
        return
    }
    Box {
        val icon =
            when (type) {
                CallEndpoint.TYPE_BLUETOOTH -> R.drawable.ic_bluetooth
                else -> R.drawable.ic_speaker
            }
        Control(icon, endpoint?.endpointName?.toString() ?: "Audio", true) { menu = true }
        DropdownMenu(menu, { menu = false }) {
            endpoints.forEach {
                DropdownMenuItem(
                    text = { Text(it.endpointName.toString()) },
                    onClick = {
                        menu = false
                        Ongoing.route(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun Control(
    icon: Int,
    label: String,
    checked: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledIconToggleButton(
            checked,
            { onClick() },
            Modifier.size(80.dp),
            enabled,
            colors =
                IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(32.dp),
                LocalContentColor.current,
            )
        }
        Text(
            label,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Labeled(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(
            label,
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun RoundButton(
    icon: Int,
    description: String,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    val colors =
        if (primary) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    FilledIconButton(onClick, modifier.size(88.dp), colors = colors) {
        Icon(painterResource(icon), description, Modifier.size(36.dp))
    }
}
