package org.nacre.phone

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Intents.Insert
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import org.nacre.core.NacreTheme

const val MAX_DIGITS = 64

private val PERMISSIONS =
    arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

enum class Tab { Favorites, Recents, Contacts, Keypad }

sealed interface Screen {
    data class Home(
        val tab: Tab,
    ) : Screen

    data class History(
        val number: String,
        val presentation: Int,
    ) : Screen

    data object Settings : Screen
}

class Navigation : ViewModel() {
    val stack = mutableStateListOf<Screen>()
    var digits by mutableStateOf("")
    var ready by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private val navigation: Navigation by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        navigation.ready = isReady()
        if (navigation.stack.isEmpty()) navigation.stack += route(intent)
        setContent {
            NacreTheme {
                if (navigation.ready) {
                    PhoneApp(navigation, ::finish)
                } else {
                    Setup()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigation.stack.clear()
        navigation.stack += route(intent)
    }

    override fun onResume() {
        super.onResume()
        navigation.ready = isReady()
    }

    private fun isDialer() =
        getSystemService(RoleManager::class.java)
            .isRoleHeld(RoleManager.ROLE_DIALER)

    private fun missing() =
        PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    private fun isReady() = isDialer() && missing().isEmpty()

    @Composable
    private fun Setup() {
        val permissions =
            rememberLauncherForActivityResult(RequestMultiplePermissions()) {
                navigation.ready = isReady()
            }
        val role =
            rememberLauncherForActivityResult(StartActivityForResult()) {
                navigation.ready = isReady()
                if (isDialer() &&
                    !navigation.ready
                ) {
                    permissions.launch(missing().toTypedArray())
                }
            }
        SetupScreen {
            if (isDialer()) {
                permissions.launch(missing().toTypedArray())
                return@SetupScreen
            }
            val roles = getSystemService(RoleManager::class.java)
            role.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    private fun route(intent: Intent): Screen {
        val data = intent.data
        val dial = intent.action in listOf(Intent.ACTION_DIAL, Intent.ACTION_VIEW)
        if (dial && data?.scheme == PhoneAccount.SCHEME_TEL) {
            navigation.digits =
                data.schemeSpecificPart
                    .orEmpty()
                    .filter(PhoneNumberUtils::isNonSeparator)
                    .take(MAX_DIGITS)
                    .removePrefix("*#*#")
            return Screen.Home(Tab.Keypad)
        }
        if (intent.type == CallLog.Calls.CONTENT_TYPE) return Screen.Home(Tab.Recents)
        if (intent.action in listOf(Intent.ACTION_DIAL, Intent.ACTION_CALL_BUTTON)) {
            return Screen.Home(Tab.Keypad)
        }
        val extra = intent.getStringExtra("tab")
        if (extra == "Call" && Ongoing.primary != null) {
            startActivity(Intent(this, InCallActivity::class.java))
        }
        val tab = Tab.entries.find { it.name == extra }
        return Screen.Home(tab ?: Tab.Recents)
    }
}

@Composable
fun PhoneApp(
    navigation: Navigation,
    finish: () -> Unit,
) {
    val stack = navigation.stack
    val back: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
    }
    BackHandler(stack.size > 1) { back() }

    when (val screen = stack.lastOrNull() ?: return) {
        is Screen.Home -> {
            HomeScreen(
                tab = screen.tab,
                navigation = navigation,
                onTab = { stack[stack.lastIndex] = Screen.Home(it) },
                onHistory = { stack += Screen.History(it.number, it.presentation) },
                onSettings = { stack += Screen.Settings },
            )
        }

        is Screen.History -> {
            HistoryScreen(screen.number, screen.presentation, back)
        }

        Screen.Settings -> {
            SettingsScreen(back)
        }
    }
}

@Composable
private fun SetupScreen(onSetup: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_call), null, Modifier.size(64.dp))
            Text(
                "Set Phone as your default phone app to make and receive calls. " +
                    "It needs access to calls, contacts and the call log on this " +
                    "device and never connects to the internet.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onSetup) { Text("Set up") }
        }
    }
}

private fun Context.toast(text: String) =
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.open(intent: Intent) {
    runCatching { startActivity(intent) }.onFailure { toast("No app can do this") }
}

private fun Context.placeCall(uri: Uri) {
    val telecom = getSystemService(TelecomManager::class.java)
    runCatching {
        telecom.placeCall(uri, Bundle())
    }.onFailure { toast("Couldn't place call") }
}

fun Context.call(number: String) {
    if (number.isNotBlank()) {
        placeCall(
            Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
        )
    }
}

fun Context.callBack(number: String) {
    if (number.isPlainNumber()) return call(number)
    val dial =
        Intent(Intent.ACTION_DIAL, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null))
    open(dial.setClass(this, MainActivity::class.java))
}

fun Context.callVoicemail() =
    placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null))

fun Context.message(number: String) =
    open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

fun Context.createContact(number: String) =
    open(
        Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI).putExtra(Insert.PHONE, number),
    )

fun Context.addToContact(number: String) =
    open(
        Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(Contacts.CONTENT_ITEM_TYPE)
            .putExtra(Insert.PHONE, number),
    )

fun Context.viewContact(uri: Uri) = open(Intent(Intent.ACTION_VIEW, uri))
