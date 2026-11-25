package org.nacre.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
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
import androidx.compose.runtime.LaunchedEffect
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

private val PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS
)

enum class Tab { Contacts, Favorites, Manage }

sealed interface Screen {
    data class Home(val tab: Tab = Tab.Contacts) : Screen

    data class Details(val contactId: Long) : Screen

    class Edit(val contactId: Long?, prefill: List<Row> = emptyList()) : Screen {
        val form = Form(prefill)
    }

    data class Pick(val kind: Kind?, val prefill: List<Row>? = null) : Screen
}

class Navigation : ViewModel() {
    val stack = mutableStateListOf<Screen>()
    var importUri by mutableStateOf<Uri?>(null)
    var granted by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private val navigation: Navigation by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        navigation.granted = PERMISSIONS.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        setContent {
            NacreTheme {
                val launcher = rememberLauncherForActivityResult(
                    RequestMultiplePermissions()
                ) { results -> navigation.granted = results.values.all { it } }
                if (!navigation.granted) {
                    PermissionScreen { launcher.launch(PERMISSIONS) }
                    return@NacreTheme
                }
                LaunchedEffect(Unit) {
                    if (navigation.stack.isEmpty()) navigation.stack += route(intent)
                }
                ContactsApp(navigation, ::finishWith)
            }
        }
    }

    private fun finishWith(uri: Uri?) {
        if (uri != null) {
            val result = Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setResult(RESULT_OK, result)
        }
        finish()
    }

    private fun route(intent: Intent): Screen {
        val uri = intent.data
        val type = intent.resolveType(this).orEmpty()
        val item = type.startsWith("vnd.android.cursor.item/")
        val home = Screen.Home(Tab.entries.find { it.name == intent.getStringExtra("tab") }
            ?: Tab.Contacts)
        return when (intent.action) {
            Intent.ACTION_VIEW, ContactsContract.QuickContact.ACTION_QUICK_CONTACT -> when {
                "vcard" in type || type == "text/directory" -> home.also {
                    navigation.importUri = uri
                }

                item -> details(uri) ?: home

                else -> home
            }

            Intent.ACTION_EDIT -> uri?.let(contentResolver::contactId)
                ?.let { Screen.Edit(it, insertRows(intent.extras)) }
                ?: Screen.Edit(null, insertRows(intent.extras))

            Intent.ACTION_INSERT -> Screen.Edit(null, insertRows(intent.extras))

            Intent.ACTION_INSERT_OR_EDIT -> Screen.Pick(null, insertRows(intent.extras))

            Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> Screen.Pick(kindOf(type))

            ContactsContract.Intents.SHOW_OR_CREATE_CONTACT -> showOrCreate(uri) ?: home

            else -> if (intent.getStringExtra("tab") == "Details") {
                contentResolver.contacts().let { all -> all.find { it.starred } ?: all.firstOrNull() }
                    ?.let { Screen.Details(it.id) } ?: home
            } else {
                home
            }
        }
    }

    private fun details(uri: Uri?) = uri?.let(contentResolver::contactId)?.let(Screen::Details)

    private fun showOrCreate(uri: Uri?): Screen? {
        val value = uri?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return null
        val phone = uri.scheme == "tel"
        val existing = runCatching {
            if (phone) contentResolver.lookupPhone(value) else contentResolver.lookupEmail(value)
        }.getOrNull()
        if (existing != null) return Screen.Details(existing)
        return Screen.Edit(null, listOf(Row(if (phone) Kind.Phone else Kind.Email, value)))
    }
}

private fun kindOf(type: String) = when (type.substringAfter('/')) {
    "phone_v2", "phone" -> Kind.Phone
    "email_v2" -> Kind.Email
    "postal-address_v2" -> Kind.Address
    else -> null
}

private fun insertRows(extras: Bundle?): List<Row> {
    if (extras == null) return emptyList()
    fun text(key: String) = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
    val name = text(Insert.NAME)
    val company = text(Insert.COMPANY)
    val title = text(Insert.JOB_TITLE)
    return buildList {
        if (name.isNotEmpty()) {
            add(Row(Kind.Name, name.substringBeforeLast(' '), name.substringAfterLast(' ', "")))
        }
        if (company.isNotEmpty() || title.isNotEmpty()) add(Row(Kind.Organization, company, title))
        listOf(Insert.PHONE, Insert.SECONDARY_PHONE, Insert.TERTIARY_PHONE)
            .map(::text).filter { it.isNotEmpty() }.forEach { add(Row(Kind.Phone, it)) }
        listOf(Insert.EMAIL, Insert.SECONDARY_EMAIL, Insert.TERTIARY_EMAIL)
            .map(::text).filter { it.isNotEmpty() }.forEach { add(Row(Kind.Email, it)) }
        text(Insert.POSTAL).takeIf { it.isNotEmpty() }?.let { add(Row(Kind.Address, it)) }
        text(Insert.NOTES).takeIf { it.isNotEmpty() }?.let { add(Row(Kind.Note, it)) }
    }
}

@Composable
fun ContactsApp(navigation: Navigation, finishWith: (Uri?) -> Unit) {
    val stack = navigation.stack
    val back: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finishWith(null)
    }
    val replace: (Screen) -> Unit = { stack[stack.lastIndex] = it }
    BackHandler(stack.size > 1) { back() }

    when (val screen = stack.lastOrNull() ?: return) {
        is Screen.Home -> HomeScreen(
            tab = screen.tab,
            navigation = navigation,
            onTab = { replace(Screen.Home(it)) },
            onOpen = { stack += Screen.Details(it) },
            onCreate = { stack += Screen.Edit(null) }
        )

        is Screen.Details -> DetailsScreen(
            contactId = screen.contactId,
            onBack = back,
            onEdit = { stack += Screen.Edit(screen.contactId) }
        )

        is Screen.Edit -> EditScreen(screen.contactId, screen.form, back) { uri, contactId ->
            when {
                stack.size == 1 -> finishWith(uri)
                screen.contactId == null && contactId != null -> replace(Screen.Details(contactId))
                else -> back()
            }
        }

        is Screen.Pick -> PickScreen(
            kind = screen.kind,
            creating = screen.prefill != null,
            onBack = back,
            onCreate = { replace(Screen.Edit(null, screen.prefill.orEmpty())) }
        ) { uri, contactId ->
            if (screen.prefill == null) finishWith(uri)
            else replace(Screen.Edit(contactId, screen.prefill))
        }
    }
}

@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            Alignment.CenterHorizontally
        ) {
            Icon(painterResource(R.drawable.ic_person), null, Modifier.size(64.dp))
            Text(
                "Contacts needs access to the contacts stored on this device. " +
                    "The app itself never connects to the internet.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onAllow) { Text("Allow access") }
        }
    }
}
