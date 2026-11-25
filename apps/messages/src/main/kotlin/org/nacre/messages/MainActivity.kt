package org.nacre.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

private const val SHARED_TEXT_LIMIT = 4000
private const val SHARED_IMAGES = 10
private val PERMISSIONS =
    arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.POST_NOTIFICATIONS,
    )
private val PRIVATE_AUTHORITIES =
    setOf("org.nacre.messages.files", "mms", "sms", "mms-sms")

sealed interface Screen {
    data class Home(
        val archived: Boolean = false,
    ) : Screen

    data class Thread(
        val id: Long,
    ) : Screen

    data object New : Screen

    data object Search : Screen

    data object Settings : Screen

    data class Viewer(
        val uri: Uri,
    ) : Screen
}

class Navigation : ViewModel() {
    val stack = mutableStateListOf<Screen>()
    var ready by mutableStateOf(false)
    var sharedText by mutableStateOf("")
    val sharedImages = mutableStateListOf<Uri>()

    fun push(screen: Screen) {
        stack += screen
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }
}

class MainActivity : ComponentActivity() {
    private val navigation: Navigation by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.setHideOverlayWindows(true)
        navigation.ready = isReady()
        if (navigation.stack.isEmpty()) route(intent)
        setContent {
            NacreTheme {
                if (navigation.ready) MessagesApp(navigation, ::finish) else Setup()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onResume() {
        super.onResume()
        navigation.ready = isReady()
    }

    private fun isDefault() =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    private fun missing() =
        PERMISSIONS.filter {
            checkSelfPermission(
                it,
            ) != PackageManager.PERMISSION_GRANTED
        }

    private fun isReady() = isDefault() && missing().isEmpty()

    @Composable
    private fun Setup() {
        val permissions =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                navigation.ready = isReady()
            }
        val role =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) {
                navigation.ready = isReady()
                if (isDefault() &&
                    !navigation.ready
                ) {
                    permissions.launch(missing().toTypedArray())
                }
            }
        SetupScreen {
            if (isDefault()) {
                permissions.launch(missing().toTypedArray())
                return@SetupScreen
            }
            val roles = getSystemService(RoleManager::class.java)
            role.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
        }
    }

    private fun route(intent: Intent) {
        val screens = listOf(Screen.Home()) + target(intent)
        navigation.stack.clear()
        navigation.stack += screens.distinct()
    }

    private fun target(intent: Intent): List<Screen> {
        val data = intent.data
        if (data?.scheme == "thread") {
            val thread = data.schemeSpecificPart.toLongOrNull() ?: return emptyList()
            return listOf(Screen.Thread(thread))
        }
        when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val addresses = data?.let(::recipients).orEmpty()
                val body = intent.getStringExtra("sms_body") ?: data?.body()
                share(body ?: intent.getStringExtra(Intent.EXTRA_TEXT), emptyList())
                if (addresses.isEmpty()) {
                    return if (data == null) emptyList() else listOf(Screen.New)
                }
                val thread = runCatching { threadFor(addresses) }.getOrNull()
                return listOf(thread?.let(Screen::Thread) ?: Screen.New)
            }

            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                share(intent.getStringExtra(Intent.EXTRA_TEXT), intent.streams())
                return listOf(Screen.New)
            }
        }
        return when (intent.getStringExtra("tab")) {
            "Archived" -> listOf(Screen.Home(archived = true))
            "Chat" -> latestThread()?.let { listOf(Screen.Thread(it)) }.orEmpty()
            "New" -> listOf(Screen.New)
            "Settings" -> listOf(Screen.Settings)
            else -> emptyList()
        }
    }

    private fun latestThread() =
        runCatching { conversations().firstOrNull()?.id }.getOrNull()

    private fun Uri.body() =
        runCatching { getQueryParameter("body") }.getOrNull()
            ?: schemeSpecificPart.substringAfter("?body=", "").takeIf { it.isNotEmpty() }

    private fun Intent.streams(): List<Uri> {
        val single = getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val many =
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        return (listOfNotNull(single) + many)
            .filter {
                it.scheme == ContentResolver.SCHEME_CONTENT &&
                    it.authority !in PRIVATE_AUTHORITIES
            }.filter {
                type?.startsWith("image/") == true ||
                    contentResolver.getType(it) in IMAGE_TYPES
            }.take(SHARED_IMAGES)
    }

    private fun share(
        text: String?,
        images: List<Uri>,
    ) {
        navigation.sharedText = text.orEmpty().take(SHARED_TEXT_LIMIT)
        navigation.sharedImages.clear()
        navigation.sharedImages += images
    }
}

@Composable
fun MessagesApp(
    navigation: Navigation,
    finish: () -> Unit,
) {
    val stack = navigation.stack
    BackHandler(stack.size > 1) { navigation.pop() }
    val back = { if (stack.size > 1) navigation.pop() else finish() }
    when (val screen = stack.lastOrNull() ?: return) {
        is Screen.Home -> HomeScreen(screen.archived, navigation, back)
        is Screen.Thread -> ThreadScreen(screen.id, navigation, back)
        Screen.New -> NewScreen(navigation, back)
        Screen.Search -> SearchScreen(navigation, back)
        Screen.Settings -> SettingsScreen(navigation, back)
        is Screen.Viewer -> ViewerScreen(screen.uri, back)
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
            Icon(painterResource(R.drawable.ic_message), null, Modifier.size(64.dp))
            Text(
                "Set Messages as your default SMS app to send and receive texts. " +
                    "It needs access to messages, contacts and your SIM on this " +
                    "device and never connects to the internet.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onSetup) { Text("Set up") }
        }
    }
}
