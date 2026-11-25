package org.nacre.calculator

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

class CalculatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

data class Calculation(
    val expression: String = "",
    val evaluated: Boolean = false,
)

private const val OPERATORS = "+−×÷^"
private const val OPERAND_ENDS = "0123456789.)πe!%"

object Store {
    private lateinit var preferences: SharedPreferences

    val calculation = MutableStateFlow(Calculation())
    val error = MutableStateFlow<String?>(null)

    fun init(context: Context) {
        val storage = context.createDeviceProtectedStorageContext()
        preferences = storage.getSharedPreferences("calculator", Context.MODE_PRIVATE)
        calculation.value =
            Calculation(
                preferences.getString("expression", "").orEmpty(),
                preferences.getBoolean("evaluated", false),
            )
    }

    fun preview(): String? {
        val (expression, evaluated) = calculation.value
        if (evaluated || expression.all { it.isDigit() || it == '.' }) return null
        return runCatching { format(evaluate(expression)) }
            .getOrNull()
            ?.takeIf { it != expression }
    }

    fun press(key: String) {
        val (expression, evaluated) = calculation.value
        val continuing = evaluated && (key in OPERATORS || key in "!%")
        val base = if (evaluated && !continuing) "" else expression
        update(type(base, key))
    }

    fun delete() =
        update(
            calculation.value.expression.let { text ->
                val function = FUNCTIONS.find { text.endsWith(it) }
                text.dropLast(function?.length ?: 1)
            },
        )

    fun clear() = update("")

    fun equals() {
        val expression = calculation.value.expression
        if (expression.isEmpty()) return
        try {
            val result = format(evaluate(expression))
            save(Calculation(result, evaluated = true))
        } catch (exception: CalculationException) {
            error.value = exception.message
        }
    }

    private fun update(expression: String) = save(Calculation(expression))

    private fun save(value: Calculation) {
        calculation.value = value
        error.value = null
        preferences.edit {
            putString("expression", value.expression)
            putBoolean("evaluated", value.evaluated)
        }
    }

    private fun type(
        text: String,
        key: String,
    ): String {
        val last = text.lastOrNull()
        val afterOperand = last != null && last in OPERAND_ENDS
        return when {
            key == "()" -> {
                text + parenthesis(text, afterOperand)
            }

            key == "." -> {
                decimal(text, last)
            }

            key in "+×÷^" && last == null -> {
                text
            }

            key in OPERATORS && last != null && last in OPERATORS -> {
                replaceOperator(text, key)
            }

            key in "!%" && !afterOperand -> {
                text
            }

            key.first().isDigit() && text.endsWith("0") &&
                !endsWithNumber(text.dropLast(1)) -> {
                text.dropLast(1) + key
            }

            else -> {
                text + key
            }
        }
    }

    private fun parenthesis(
        text: String,
        afterOperand: Boolean,
    ): String {
        val open = text.count { it == '(' } > text.count { it == ')' }
        return if (open && afterOperand) ")" else "("
    }

    private fun decimal(
        text: String,
        last: Char?,
    ): String {
        val number = text.takeLastWhile { it.isDigit() || it == '.' }
        if ('.' in number) return text
        return if (last?.isDigit() == true) "$text." else "${text}0."
    }

    private fun replaceOperator(
        text: String,
        key: String,
    ): String {
        if (key == "−" && text.last() in "×÷^") return text + key
        val operand = text.dropLastWhile { it in OPERATORS }
        return if (operand.isEmpty() && key != "−") operand else operand + key
    }

    private fun endsWithNumber(text: String) =
        text.lastOrNull()?.let {
            it.isDigit() || it == '.'
        } == true
}
