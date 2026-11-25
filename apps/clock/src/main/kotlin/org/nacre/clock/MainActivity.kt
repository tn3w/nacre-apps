package org.nacre.clock

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import org.nacre.core.NacreTheme

enum class Tab(val label: String, val icon: Int) {
    Alarm("Alarm", R.drawable.ic_alarm),
    Clock("Clock", R.drawable.ic_clock),
    Timer("Timer", R.drawable.ic_timer)
}

fun openTab(context: Context, tab: Tab): PendingIntent = PendingIntent.getActivity(
    context,
    tab.ordinal,
    Intent(context, MainActivity::class.java).putExtra("tab", tab.name),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

class MainActivity : ComponentActivity() {
    private val tab = mutableStateOf(Tab.Alarm)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tab.value = Tab.valueOf(savedInstanceState?.getString("tab") ?: Tab.Alarm.name)
        handle(intent)
        setContent { NacreTheme { ClockApp(tab) } }

        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 0)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra("tab")?.let { tab.value = Tab.valueOf(it) }
    }
}

@Composable
fun ClockApp(tab: MutableState<Tab>) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab.value == it,
                        onClick = { tab.value = it },
                        icon = { Icon(painterResource(it.icon), null) },
                        label = { Text(it.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab.value) {
                Tab.Alarm -> AlarmScreen()
                Tab.Clock -> ClockScreen()
                Tab.Timer -> TimerScreen()
            }
        }
    }
}
