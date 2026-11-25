package org.nacre.calculator

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.DecimalFormatSymbols
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class CalculatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

data class Entry(val expression: String, val result: String)

data class Calculation(val expression: String = "", val evaluated: Boolean = false)

private const val OPERATORS = "+−×÷^"
private const val PASTABLE = "0123456789.+−×÷^%!()πeE√"
private const val OPERAND_ENDS = "0123456789.)πe!%"
private const val MAX_LENGTH = 1000

object Store {
    private lateinit var preferences: SharedPreferences

    val calculation = MutableStateFlow(Calculation())
    val error = MutableStateFlow<String?>(null)
    val scientific = MutableStateFlow(false)
    val degrees = MutableStateFlow(false)
    val history = MutableStateFlow(emptyList<Entry>())
    val rates = MutableStateFlow(emptyMap<String, String>())

    fun init(context: Context) {
        preferences = context.getSharedPreferences("calculator", Context.MODE_PRIVATE)
        calculation.value = Calculation(
            preferences.getString("expression", "").orEmpty().take(MAX_LENGTH),
            preferences.getBoolean("evaluated", false)
        )
        scientific.value = preferences.getBoolean("scientific", false)
        degrees.value = preferences.getBoolean("degrees", false)
        history.value = readHistory()
        val stored = runCatching { JSONObject(preferences.getString("rates", "{}")!!) }
            .getOrDefault(JSONObject())
        rates.value = stored.keys().asSequence().associateWith { stored.getString(it) }
    }

    fun setScientific(value: Boolean) {
        scientific.value = value
        preferences.edit { putBoolean("scientific", value) }
    }

    fun toggleDegrees() {
        degrees.value = !degrees.value
        preferences.edit { putBoolean("degrees", degrees.value) }
    }

    fun preview(): String? {
        val (expression, evaluated) = calculation.value
        if (evaluated || expression.all { it.isDigit() || it == '.' }) return null
        return runCatching { format(evaluate(expression, degrees.value)) }.getOrNull()
            ?.takeIf { it != expression }
    }

    fun press(key: String) {
        val (expression, evaluated) = calculation.value
        val continuing = evaluated && key.first() in "$OPERATORS!%"
        val base = if (evaluated && !continuing) "" else expression
        update(type(base, key))
    }

    fun delete() = update(
        calculation.value.expression.let { text ->
            val function = FUNCTIONS.find { text.endsWith(it) }
            text.dropLast(function?.length ?: 1)
        }
    )

    fun clear() = update("")

    fun equals() {
        val (expression, evaluated) = calculation.value
        if (expression.isEmpty() || evaluated) return
        try {
            val result = format(evaluate(expression, degrees.value))
            save(Calculation(result, evaluated = true))
            saveHistory(listOf(Entry(expression, result)) + history.value.take(99))
        } catch (exception: CalculationException) {
            error.value = exception.message
        }
    }

    fun recall(value: String) {
        val (expression, evaluated) = calculation.value
        update(if (evaluated) value else expression + value)
    }

    fun copyText(): String {
        val (expression, evaluated) = calculation.value
        val value = if (evaluated) expression else preview() ?: expression
        return value.replace('−', '-').replace('×', '*').replace('÷', '/')
    }

    fun paste(text: String) {
        val symbols = DecimalFormatSymbols.getInstance()
        val cleaned = text.trim()
            .replace(symbols.groupingSeparator.toString(), "")
            .replace(symbols.decimalSeparator, '.')
            .replace('-', '−').replace('*', '×').replace('/', '÷')
            .filter { it in PASTABLE }
        if (cleaned.isNotEmpty()) recall(cleaned)
    }

    fun typeCharacter(character: Char): Boolean {
        val key = when (character) {
            '-' -> "−"
            '*' -> "×"
            '/' -> "÷"
            '=', '\n' -> return true.also { equals() }
            else -> character.toString().takeIf { character in "0123456789.+^!%()" }
        } ?: return false
        press(key)
        return true
    }

    fun setRate(code: String, usd: String?) {
        rates.value = if (usd == null) rates.value - code else rates.value + (code to usd)
        preferences.edit { putString("rates", JSONObject(rates.value).toString()) }
    }

    fun setting(key: String) = preferences.getString(key, null)

    fun putSetting(key: String, value: String) = preferences.edit {
        putString(key, value)
    }

    fun clearHistory() = saveHistory(emptyList())

    private fun saveHistory(entries: List<Entry>) {
        history.value = entries
        val array = JSONArray(
            entries.map {
                JSONObject().put("expression", it.expression).put("result", it.result)
            }
        )
        preferences.edit { putString("history", array.toString()) }
    }

    private fun readHistory(): List<Entry> {
        val array = runCatching { JSONArray(preferences.getString("history", "[]")) }
            .getOrDefault(JSONArray())
        return List(array.length()) { array.optJSONObject(it) }.mapNotNull {
            val expression = it?.optString("expression").orEmpty()
            val result = it?.optString("result").orEmpty()
            Entry(expression, result).takeIf {
                expression.isNotEmpty() &&
                    result.isNotEmpty()
            }
        }
    }

    private fun update(expression: String) {
        if (expression.length > MAX_LENGTH) return
        save(Calculation(expression))
    }

    private fun save(value: Calculation) {
        calculation.value = value
        error.value = null
        preferences.edit {
            putString("expression", value.expression)
            putBoolean("evaluated", value.evaluated)
        }
    }

    private fun type(text: String, key: String): String {
        val last = text.lastOrNull()
        val afterOperand = last != null && last in OPERAND_ENDS
        return when {
            key == "()" -> text + parenthesis(text, afterOperand)

            key == "." -> decimal(text, last)

            key.first() in "+×÷^" && last == null -> text

            key.length == 1 && key in OPERATORS && last != null && last in OPERATORS ->
                replaceOperator(text, key)

            key.first() in "^!%" && !afterOperand -> text

            key.first().isDigit() && text.endsWith("0") &&
                !endsWithNumber(text.dropLast(1)) ->
                text.dropLast(1) + key

            else -> text + key
        }
    }

    private fun parenthesis(text: String, afterOperand: Boolean): String {
        val open = text.count { it == '(' } > text.count { it == ')' }
        return if (open && afterOperand) ")" else "("
    }

    private fun decimal(text: String, last: Char?): String {
        val number = text.takeLastWhile { it.isDigit() || it == '.' }
        if ('.' in number) return text
        return if (last?.isDigit() == true) "$text." else "${text}0."
    }

    private fun replaceOperator(text: String, key: String): String {
        if (key == "−" && text.last() in "×÷^") return text + key
        val operand = text.dropLastWhile { it in OPERATORS }
        return if (operand.isEmpty() && key != "−") operand else operand + key
    }

    private fun endsWithNumber(text: String) =
        text.lastOrNull()?.let { it.isDigit() || it == '.' } == true
}
