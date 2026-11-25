package org.nacre.calculator

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.painterResource
import org.nacre.core.NacreTheme

enum class Page { Calculator, History, Converter }

class MainActivity : ComponentActivity() {
    private val page = mutableStateOf(Page.Calculator)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        Page.entries.find { it.name == savedInstanceState?.getString("page") }
            ?.let { page.value = it }
        if (savedInstanceState == null) handle(intent)
        setContent { NacreTheme { CalculatorApp(page) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.value.name)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (page.value != Page.Calculator || event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> true.also { Store.delete() }

            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_CLEAR -> true.also { Store.clear() }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true.also {
                Store.equals()
            }

            else -> Store.typeCharacter(event.unicodeChar.toChar())
        }
        return handled || super.dispatchKeyEvent(event)
    }

    private fun handle(intent: Intent) {
        when (val tab = intent.getStringExtra("tab")) {
            "Basic", "Scientific" -> {
                page.value = Page.Calculator
                Store.setScientific(tab == "Scientific")
            }

            else -> Page.entries.find { it.name == tab }?.let { page.value = it }
        }
    }
}

@Composable
fun CalculatorApp(page: MutableState<Page>) {
    val back = { page.value = Page.Calculator }
    BackHandler(page.value != Page.Calculator, back)
    when (page.value) {
        Page.Calculator -> CalculatorScreen {
            IconButton(onClick = { page.value = Page.History }) {
                Icon(painterResource(R.drawable.ic_history), "History")
            }
            IconButton(onClick = { page.value = Page.Converter }) {
                Icon(painterResource(R.drawable.ic_convert), "Converter")
            }
        }

        Page.History -> HistoryScreen(back)

        Page.Converter -> ConverterScreen(back)
    }
}
