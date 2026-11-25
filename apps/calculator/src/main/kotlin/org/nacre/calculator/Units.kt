package org.nacre.calculator

import java.math.BigDecimal
import java.math.MathContext

private val precision = MathContext.DECIMAL128

class Measure(
    val symbol: String,
    val name: String,
    val factor: BigDecimal,
    val offset: BigDecimal = BigDecimal.ZERO,
    val rate: String? = null
) {
    fun toBase(value: BigDecimal): BigDecimal =
        value.add(offset).multiply(factor, precision)

    fun fromBase(value: BigDecimal): BigDecimal =
        value.divide(factor, precision).subtract(offset, precision)
}

private fun unit(symbol: String, name: String, factor: String) =
    Measure(symbol, name, BigDecimal(factor))

private fun fraction(numerator: String, denominator: String) =
    BigDecimal(numerator).divide(BigDecimal(denominator), precision)

class Coin(val code: String, val name: String, val usd: String)

val COINS = listOf(
    Coin("USD", "US dollar", "1"),
    Coin("EUR", "Euro", "1.16"),
    Coin("GBP", "British pound", "1.33"),
    Coin("JPY", "Japanese yen", "0.0066"),
    Coin("CHF", "Swiss franc", "1.25"),
    Coin("CNY", "Chinese yuan", "0.14"),
    Coin("CAD", "Canadian dollar", "0.71"),
    Coin("AUD", "Australian dollar", "0.65"),
    Coin("INR", "Indian rupee", "0.0113"),
    Coin("BRL", "Brazilian real", "0.19"),
    Coin("MXN", "Mexican peso", "0.054"),
    Coin("KRW", "South Korean won", "0.00069"),
    Coin("SEK", "Swedish krona", "0.105"),
    Coin("NOK", "Norwegian krone", "0.099"),
    Coin("DKK", "Danish krone", "0.155"),
    Coin("PLN", "Polish złoty", "0.27"),
    Coin("CZK", "Czech koruna", "0.048"),
    Coin("TRY", "Turkish lira", "0.024"),
    Coin("ZAR", "South African rand", "0.057"),
    Coin("BTC", "Bitcoin", "90000"),
    Coin("ETH", "Ether", "3000"),
    Coin("XMR", "Monero", "350"),
    Coin("LTC", "Litecoin", "90"),
    Coin("SOL", "Solana", "140")
)

private val CRYPTO_UNITS = mapOf(
    "BTC" to listOf(
        Triple("mBTC", "Millibitcoin", "0.001"),
        Triple("bits", "Microbitcoin", "0.000001"),
        Triple("sat", "Satoshi", "0.00000001")
    ),
    "ETH" to listOf(
        Triple("gwei", "Gwei", "0.000000001"),
        Triple("wei", "Wei", "0.000000000000000001")
    ),
    "XMR" to listOf(Triple("piconero", "Piconero", "0.000000000001")),
    "LTC" to listOf(Triple("litoshi", "Litoshi", "0.00000001")),
    "SOL" to listOf(Triple("lamport", "Lamport", "0.000000001"))
)

fun currencies(rates: Map<String, String>): List<Measure> = COINS.flatMap { coin ->
    val stored = rates[coin.code]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val usd = stored ?: BigDecimal(coin.usd)
    val main = Measure(coin.code, coin.name, usd, rate = coin.code)
    val parts = CRYPTO_UNITS[coin.code].orEmpty().map { (symbol, name, share) ->
        Measure(symbol, name, usd.multiply(BigDecimal(share), precision))
    }
    listOf(main) + parts
}

enum class Category(val title: String, val units: List<Measure>) {
    Currency("Currency", emptyList()),
    Length(
        "Length",
        listOf(
            unit("mm", "Millimeter", "0.001"),
            unit("cm", "Centimeter", "0.01"),
            unit("m", "Meter", "1"),
            unit("km", "Kilometer", "1000"),
            unit("in", "Inch", "0.0254"),
            unit("ft", "Foot", "0.3048"),
            unit("yd", "Yard", "0.9144"),
            unit("mi", "Mile", "1609.344"),
            unit("nmi", "Nautical mile", "1852")
        )
    ),
    Area(
        "Area",
        listOf(
            unit("cm²", "Square centimeter", "0.0001"),
            unit("m²", "Square meter", "1"),
            unit("ha", "Hectare", "10000"),
            unit("km²", "Square kilometer", "1000000"),
            unit("ft²", "Square foot", "0.09290304"),
            unit("ac", "Acre", "4046.8564224"),
            unit("mi²", "Square mile", "2589988.110336")
        )
    ),
    Volume(
        "Volume",
        listOf(
            unit("mL", "Milliliter", "0.001"),
            unit("L", "Liter", "1"),
            unit("m³", "Cubic meter", "1000"),
            unit("tsp", "Teaspoon (US)", "0.00492892159375"),
            unit("tbsp", "Tablespoon (US)", "0.01478676478125"),
            unit("fl oz", "Fluid ounce (US)", "0.0295735295625"),
            unit("cup", "Cup (US)", "0.2365882365"),
            unit("gal", "Gallon (US)", "3.785411784"),
            unit("gal UK", "Gallon (UK)", "4.54609")
        )
    ),
    Mass(
        "Mass",
        listOf(
            unit("mg", "Milligram", "0.000001"),
            unit("g", "Gram", "0.001"),
            unit("kg", "Kilogram", "1"),
            unit("t", "Tonne", "1000"),
            unit("oz", "Ounce", "0.028349523125"),
            unit("lb", "Pound", "0.45359237"),
            unit("st", "Stone", "6.35029318")
        )
    ),
    Temperature(
        "Temperature",
        listOf(
            Measure("°C", "Celsius", BigDecimal.ONE, BigDecimal("273.15")),
            Measure("°F", "Fahrenheit", fraction("5", "9"), BigDecimal("459.67")),
            Measure("K", "Kelvin", BigDecimal.ONE)
        )
    ),
    Speed(
        "Speed",
        listOf(
            unit("m/s", "Meter per second", "1"),
            Measure("km/h", "Kilometer per hour", fraction("1000", "3600")),
            Measure("mph", "Mile per hour", fraction("1609.344", "3600")),
            Measure("kn", "Knot", fraction("1852", "3600"))
        )
    ),
    Time(
        "Time",
        listOf(
            unit("ms", "Millisecond", "0.001"),
            unit("s", "Second", "1"),
            unit("min", "Minute", "60"),
            unit("h", "Hour", "3600"),
            unit("d", "Day", "86400"),
            unit("wk", "Week", "604800"),
            unit("yr", "Year", "31556952")
        )
    ),
    Data(
        "Data",
        listOf(
            unit("bit", "Bit", "0.125"),
            unit("B", "Byte", "1"),
            unit("kB", "Kilobyte", "1000"),
            unit("MB", "Megabyte", "1000000"),
            unit("GB", "Gigabyte", "1000000000"),
            unit("TB", "Terabyte", "1000000000000"),
            unit("KiB", "Kibibyte", "1024"),
            unit("MiB", "Mebibyte", "1048576"),
            unit("GiB", "Gibibyte", "1073741824"),
            unit("TiB", "Tebibyte", "1099511627776")
        )
    )
}
