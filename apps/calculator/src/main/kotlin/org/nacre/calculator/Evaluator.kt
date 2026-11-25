package org.nacre.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols

class CalculationException(message: String) : Exception(message)

private val precision = MathContext.DECIMAL128
private val PI = BigDecimal("3.141592653589793238462643383279503")
private val E = BigDecimal("2.718281828459045235360287471352662")
private val NUMBER = Regex("""(\d+\.?\d*|\.\d+)(E−?\d+)?""")
private val DISPLAY_NUMBER = Regex("""E−?\d+|(\d+)(\.\d*)?""")
private const val OPERAND_STARTS = "0123456789.(πe√sctl"

val FUNCTIONS =
    listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "sin(", "cos(", "tan(", "ln(", "log(")

fun evaluate(text: String, degrees: Boolean = false): BigDecimal = try {
    Evaluator(text, degrees).evaluate()
} catch (_: ArithmeticException) {
    throw CalculationException("Too large")
} catch (_: NumberFormatException) {
    throw CalculationException("Error")
} catch (_: StackOverflowError) {
    throw CalculationException("Too complex")
}

private class Evaluator(private val text: String, private val degrees: Boolean) {
    private var position = 0

    fun evaluate(): BigDecimal {
        val value = expression()
        if (position < text.length) fail()
        return value
    }

    private fun expression(): BigDecimal {
        var value = term()
        while (true) {
            value = when {
                take("+") -> value.add(term(), precision)
                take("−") -> value.subtract(term(), precision)
                else -> return value
            }
        }
    }

    private fun term(): BigDecimal {
        var value = unary()
        while (true) {
            value = when {
                take("×") -> value.multiply(unary(), precision)
                take("÷") -> divide(value, unary())
                startsOperand() -> value.multiply(unary(), precision)
                else -> return value
            }
        }
    }

    private fun unary(): BigDecimal = if (take("−")) unary().negate() else power()

    private fun power(): BigDecimal {
        val base = postfix()
        return if (take("^")) raise(base, unary()) else base
    }

    private fun postfix(): BigDecimal {
        var value = primary()
        while (true) {
            value = when {
                take("!") -> factorial(value)
                take("%") -> value.movePointLeft(2)
                else -> return value
            }
        }
    }

    private fun primary(): BigDecimal {
        if (take("(")) return group()
        if (take("π")) return PI
        if (take("e")) return E
        if (take("√")) return root(unary())
        val function = FUNCTIONS.find { text.startsWith(it, position) } ?: return number()
        position += function.length
        return apply(function, group())
    }

    private fun group() = expression().also { take(")") }

    private fun number(): BigDecimal {
        val match = NUMBER.matchAt(text, position) ?: fail()
        position = match.range.last + 1
        return BigDecimal(match.value.replace('−', '-'))
    }

    private fun apply(function: String, value: BigDecimal): BigDecimal {
        val angle = if (degrees) Math.toRadians(value.toDouble()) else value.toDouble()
        return when (function) {
            "sin(" -> bounded(Math.sin(angle))
            "cos(" -> bounded(Math.cos(angle))
            "tan(" -> tangent(angle)
            "ln(" -> logarithm(value, Math::log)
            "log(" -> logarithm(value, Math::log10)
            else -> inverse(function, value.toDouble())
        }
    }

    private fun tangent(angle: Double): BigDecimal {
        val cosine = bounded(Math.cos(angle))
        if (cosine.signum() == 0) fail("Undefined")
        return bounded(Math.sin(angle)).divide(cosine, precision)
    }

    private fun inverse(function: String, value: Double): BigDecimal {
        val radians = when (function) {
            "sin⁻¹(" -> Math.asin(value)
            "cos⁻¹(" -> Math.acos(value)
            else -> Math.atan(value)
        }
        return fromDouble(if (degrees) Math.toDegrees(radians) else radians)
    }

    private fun logarithm(value: BigDecimal, function: (Double) -> Double): BigDecimal {
        if (value.signum() <= 0) fail("Undefined")
        return fromDouble(function(value.toDouble()))
    }

    private fun divide(value: BigDecimal, divisor: BigDecimal): BigDecimal {
        if (divisor.signum() == 0) fail("Can't divide by 0")
        return value.divide(divisor, precision)
    }

    private fun raise(base: BigDecimal, exponent: BigDecimal): BigDecimal {
        val whole = exponent.stripTrailingZeros().scale() <= 0
        if (!whole || exponent.abs() > BigDecimal(999_999_999)) {
            return fromDouble(Math.pow(base.toDouble(), exponent.toDouble()))
        }
        if (base.signum() == 0 && exponent.signum() < 0) fail("Can't divide by 0")
        return base.pow(exponent.toInt(), precision)
    }

    private fun root(value: BigDecimal): BigDecimal {
        if (value.signum() < 0) fail("Undefined")
        return value.sqrt(precision)
    }

    private fun factorial(value: BigDecimal): BigDecimal {
        if (value.signum() < 0 ||
            value.stripTrailingZeros().scale() > 0
        ) {
            fail("Undefined")
        }
        if (value > BigDecimal(10_000)) fail("Too large")
        return (2..value.toInt()).fold(BigDecimal.ONE) { product, factor ->
            product.multiply(BigDecimal(factor), precision)
        }
    }

    private fun bounded(value: Double): BigDecimal {
        if (value.isNaN()) fail("Undefined")
        return BigDecimal(value).setScale(15, RoundingMode.HALF_EVEN).stripTrailingZeros()
    }

    private fun fromDouble(value: Double): BigDecimal {
        if (value.isNaN()) fail("Undefined")
        if (value.isInfinite()) fail("Too large")
        return BigDecimal(value).round(MathContext(15))
    }

    private fun startsOperand() =
        position < text.length && text[position] in OPERAND_STARTS

    private fun take(token: String) = text.startsWith(token, position).also {
        if (it) position += token.length
    }

    private fun fail(message: String = "Error"): Nothing =
        throw CalculationException(message)
}

fun format(value: BigDecimal, digits: Int = 16): String {
    val rounded = value.round(MathContext(digits)).stripTrailingZeros()
    if (rounded.signum() == 0) return "0"
    val exponent = rounded.precision() - rounded.scale() - 1
    val text = if (exponent in -6..15) {
        rounded.toPlainString()
    } else {
        "${rounded.movePointLeft(exponent).toPlainString()}E$exponent"
    }
    return text.replace('-', '−')
}

fun display(text: String): String {
    val symbols = DecimalFormatSymbols.getInstance()
    return DISPLAY_NUMBER.replace(text) { match ->
        val (integer, fraction) = match.destructured
        if (integer.isEmpty()) return@replace match.value
        val grouped = integer.reversed().chunked(3).joinToString(
            symbols.groupingSeparator.toString()
        ).reversed()
        grouped + fraction.replace('.', symbols.decimalSeparator)
    }
}
