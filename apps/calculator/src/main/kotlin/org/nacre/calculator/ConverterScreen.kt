package org.nacre.calculator

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

@Composable
fun ConverterScreen(onBack: () -> Unit) {
    val rates by Store.rates.collectAsState()
    var category by rememberSaveable {
        mutableStateOf(
            Category.entries.find { it.name == Store.setting("category") }
                ?: Category.Currency
        )
    }
    val units = if (category == Category.Currency) currencies(rates) else category.units
    var symbol by rememberSaveable(category) {
        mutableStateOf(Store.setting("unit.${category.name}") ?: units.first().symbol)
    }
    var input by rememberSaveable { mutableStateOf(Store.setting("input") ?: "1") }
    var editing by remember { mutableStateOf<Measure?>(null) }
    val source = units.find { it.symbol == symbol } ?: units.first()
    val base = remember(source, input) {
        runCatching { source.toBase(evaluate(normalize(input))) }.getOrNull()
    }

    fun select(unit: Measure, value: String) {
        symbol = unit.symbol
        input = value
        Store.putSetting("unit.${category.name}", unit.symbol)
        Store.putSetting("input", value)
    }

    Page("Converter", onBack) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            Arrangement.spacedBy(8.dp)
        ) {
            Category.entries.forEach {
                FilterChip(
                    selected = it == category,
                    onClick = {
                        category = it
                        Store.putSetting("category", it.name)
                    },
                    label = { Text(it.title) }
                )
            }
        }
        Row(
            Modifier.padding(16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                input,
                { select(source, it) },
                Modifier.weight(1f),
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                isError = base == null && input.isNotBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            UnitPicker(units, source) { select(it, input) }
        }
        if (category == Category.Currency) {
            Text(
                "Offline rates. Long-press a currency to set its value in USD.",
                Modifier.padding(horizontal = 24.dp),
                MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(Modifier.imePadding()) {
            items(units.filter { it !== source }, key = { it.symbol }) { unit ->
                val value = base?.let {
                    runCatching { format(unit.fromBase(it), digits = 10) }.getOrNull()
                }
                UnitRow(
                    unit,
                    value,
                    onClick = { value?.let { select(unit, it.replace('−', '-')) } },
                    onLongClick = { if (unit.rate != null) editing = unit }
                )
            }
        }
    }

    editing?.let { unit -> RateDialog(unit) { editing = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnitRow(
    unit: Measure,
    value: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(
            onLongClick = onLongClick,
            onClick = onClick
        )
            .padding(24.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(unit.name)
            Text(
                unit.symbol,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(value?.let(::display).orEmpty(), fontSize = 22.sp)
    }
}

@Composable
private fun UnitPicker(
    units: List<Measure>,
    selected: Measure,
    onSelect: (Measure) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(selected.symbol, style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(open, { open = false }) {
            units.forEach {
                DropdownMenuItem(
                    text = { Text("${it.symbol}  ·  ${it.name}") },
                    onClick = {
                        onSelect(it)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RateDialog(unit: Measure, onDismiss: () -> Unit) {
    val code = unit.rate ?: return
    var field by remember {
        val text = unit.factor.stripTrailingZeros().toPlainString()
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val rate = field.text.replace(',', '.').toBigDecimalOrNull()?.takeIf {
        it >
            BigDecimal.ZERO
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("1 $code in USD") },
        text = {
            OutlinedTextField(
                field,
                { field = it },
                Modifier.focusRequester(focus),
                singleLine = true,
                isError = rate == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    Store.setRate(code, rate?.toPlainString())
                    onDismiss()
                },
                enabled = rate != null
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                Store.setRate(code, null)
                onDismiss()
            }) { Text("Reset") }
        }
    )
}

private fun normalize(input: String) = input.filterNot(Char::isWhitespace)
    .replace(',', '.').replace('-', '−').replace('*', '×').replace('/', '÷')
