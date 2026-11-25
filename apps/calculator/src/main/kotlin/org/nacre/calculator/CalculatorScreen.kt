package org.nacre.calculator

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DecimalFormatSymbols

private enum class Kind { Digit, Operator, Action, Equals, Function }

private class Function(
    val label: String,
    val key: String,
    val inverseLabel: String = label,
    val inverseKey: String = key
)

private val KEYS = listOf(
    "AC", "()", "%", "÷",
    "7", "8", "9", "×",
    "4", "5", "6", "−",
    "1", "2", "3", "+",
    "0", ".", "⌫", "="
)

private val SCIENTIFIC = listOf(
    Function("sin", "sin(", "sin⁻¹", "sin⁻¹("),
    Function("cos", "cos(", "cos⁻¹", "cos⁻¹("),
    Function("tan", "tan(", "tan⁻¹", "tan⁻¹("),
    Function("π", "π"),
    Function("√", "√", "x²", "^2"),
    Function("xʸ", "^"),
    Function("ln", "ln(", "eˣ", "e^("),
    Function("log", "log(", "10ˣ", "10^("),
    Function("x!", "!"),
    Function("e", "e")
)

@Composable
fun CalculatorScreen(actions: @Composable () -> Unit = {}) {
    val scientific by Store.scientific.collectAsState()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp, 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions()
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Store.setScientific(!scientific) }) {
                    Icon(
                        painterResource(R.drawable.ic_expand),
                        if (scientific) "Hide functions" else "Show functions",
                        Modifier.rotate(if (scientific) 180f else 0f)
                    )
                }
            }
            Display(Modifier.weight(1f).fillMaxWidth())
            if (scientific) ScientificKeys()
            KEYS.chunked(4).forEach { row ->
                Row(Modifier.padding(vertical = 4.dp), Arrangement.spacedBy(8.dp)) {
                    row.forEach { MainKey(it, Modifier.weight(1f), scientific) }
                }
            }
        }
    }
}

@Composable
private fun Display(modifier: Modifier) {
    val calculation by Store.calculation.collectAsState()
    val error by Store.error.collectAsState()
    val degrees by Store.degrees.collectAsState()
    val expression = display(calculation.expression)
    val size = when {
        expression.length <= 9 -> 72.sp
        expression.length <= 14 -> 52.sp
        else -> 40.sp
    }

    Column(modifier.padding(8.dp), Arrangement.Bottom, Alignment.End) {
        Text(
            expression,
            Modifier.weight(1f, fill = false).verticalScroll(
                rememberScrollState(),
                reverseScrolling = true
            ),
            fontSize = size,
            lineHeight = size * 1.1f,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End
        )
        Text(
            error ?: degrees.let { Store.preview() }?.let(::display).orEmpty(),
            color = MaterialTheme.colorScheme.outline,
            fontSize = 32.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ScientificKeys() {
    val degrees by Store.degrees.collectAsState()
    var inverse by rememberSaveable { mutableStateOf(false) }
    val toggles = listOf(
        Function(if (degrees) "deg" else "rad", "angle"),
        Function("inv", "inverse")
    )
    (toggles + SCIENTIFIC).chunked(6).forEach { row ->
        Row(Modifier.padding(vertical = 3.dp), Arrangement.spacedBy(6.dp)) {
            row.forEach { function ->
                val label = if (inverse) function.inverseLabel else function.label
                val active = function.key == "inverse" && inverse
                val kind = if (active) Kind.Equals else Kind.Function
                Key(label, kind, Modifier.weight(1f), 44.dp, 18, {
                    when (function.key) {
                        "angle" -> Store.toggleDegrees()

                        "inverse" -> inverse = !inverse

                        else -> {
                            Store.press(
                                if (inverse) function.inverseKey else function.key
                            )
                            inverse = false
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun MainKey(key: String, modifier: Modifier, compact: Boolean) {
    val kind = when {
        key == "=" -> Kind.Equals
        key in listOf("AC", "()", "%") -> Kind.Action
        key in "÷×−+" -> Kind.Operator
        else -> Kind.Digit
    }
    val label = when (key) {
        "." -> DecimalFormatSymbols.getInstance().decimalSeparator.toString()
        "()" -> "( )"
        else -> key
    }
    val onClick: () -> Unit = when (key) {
        "AC" -> Store::clear
        "⌫" -> Store::delete
        "=" -> Store::equals
        else -> { -> Store.press(key) }
    }
    val height = if (compact) 60.dp else 72.dp
    Key(label, kind, modifier, height, 30, onClick, Store::clear.takeIf { key == "⌫" })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    label: String,
    kind: Kind,
    modifier: Modifier,
    height: Dp,
    fontSize: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val (background, content) = when (kind) {
        Kind.Digit -> colors.surfaceVariant to colors.onSurfaceVariant
        Kind.Operator -> colors.secondaryContainer to colors.onSecondaryContainer
        Kind.Action -> colors.tertiaryContainer to colors.onTertiaryContainer
        Kind.Equals -> colors.primary to colors.onPrimary
        Kind.Function -> colors.surface to colors.onSurface
    }
    Box(
        modifier.height(
            height
        ).clip(CircleShape).background(background).combinedClickable(
            onLongClick = onLongClick,
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        ),
        Alignment.Center
    ) {
        if (label == "⌫") {
            Icon(painterResource(R.drawable.ic_backspace), "Delete", tint = content)
        } else {
            Text(label, color = content, fontSize = fontSize.sp)
        }
    }
}
