package org.nacre.keyboard

enum class Action {
    Text,
    Shift,
    Delete,
    Enter,
    Space,
    Symbols,
    More,
    Letters,
    Language,
    Emoji,
}

enum class Page { Letters, Symbols, More, Numbers, Phone }

data class Key(
    val text: String,
    val action: Action = Action.Text,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String = "",
    val hold: Action? = null,
) {
    val flexible get() = width == 0f
}

enum class Language(
    val title: String,
    val code: String,
    val rows: List<String>,
) {
    English("English", "en", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
    German("Deutsch", "de", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm")),
    French("Français", "fr", listOf("azertyuiop", "qsdfghjklm", "wxcvbn'")),
    Spanish("Español", "es", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")),
    ;

    companion object {
        fun of(code: String?) = entries.find { it.code == code }
    }
}

private val accents =
    mapOf(
        'a' to "àáâäæãåā",
        'c' to "çćč",
        'e' to "èéêëēėę€",
        'g' to "ğ",
        'i' to "ìíîïīį",
        'l' to "ł",
        'n' to "ñń",
        'o' to "òóôöõœøō",
        's' to "ßśš",
        'u' to "ùúûüū",
        'y' to "ÿý",
        'z' to "žźż",
    )

private val symbolPopups =
    mapOf(
        "0" to "ⁿ∅°",
        "1" to "¹½⅓¼",
        "2" to "²⅔",
        "3" to "³¾",
        "4" to "⁴",
        "$" to "€£¥¢₹₽",
        "-" to "–—_·",
        "\"" to "“”„«»",
        "'" to "‘’‚‹›",
        "?" to "¿",
        "!" to "¡",
        "%" to "‰",
        "*" to "★†‡",
        "+" to "±",
        "=" to "≠≈∞",
        "<" to "≤«‹",
        ">" to "≥»›",
        "(" to "[{<",
        ")" to "]}>",
        "&" to "§",
        "." to "…",
    )

const val PUNCTUATION = ".,?!'\"-:;/@#&()"

private fun popupOf(text: String) = symbolPopups[text].orEmpty().map(Char::toString)

private fun symbolRow(chars: String) = chars.map { Key("$it", popup = popupOf("$it")) }

private fun letterRow(
    chars: String,
    hints: String,
) = chars.mapIndexed { index, char ->
    val hint = hints.getOrNull(index)?.toString().orEmpty()
    val extras = accents[char].orEmpty().map(Char::toString)
    Key("$char", popup = listOfNotNull(hint.ifEmpty { null }) + extras, hint = hint)
}

private val shift = Key("", Action.Shift, 0f)
private val delete = Key("", Action.Delete, 0f)

fun layout(
    page: Page,
    language: Language,
    numberRow: Boolean,
    comma: String,
    languages: Int,
): List<List<Key>> {
    val switcher = if (languages > 1) Key("", Action.Language) else Key("", Action.Emoji)

    fun bottom(first: Key) =
        listOf(
            first,
            Key(comma, popup = popupOf(comma), hold = Action.Emoji),
            switcher,
            Key("", Action.Space, 0f),
            Key(".", popup = PUNCTUATION.drop(1).map(Char::toString)),
            Key("", Action.Enter, 1.5f),
        )
    val digits = "1234567890"
    return when (page) {
        Page.Letters -> {
            val hints = if (numberRow) "" else digits
            val (top, middle, last) = language.rows
            listOfNotNull(
                if (numberRow) symbolRow(digits) else null,
                letterRow(top, hints),
                letterRow(middle, ""),
                listOf(shift) + letterRow(last, "") + delete,
                bottom(Key("?123", Action.Symbols, 1.5f)),
            )
        }

        Page.Symbols -> {
            listOf(
                symbolRow(digits),
                symbolRow("@#\$_&-+()/"),
                listOf(Key("=\\<", Action.More, 0f)) + symbolRow("*\"':;!?") + delete,
                bottom(Key("ABC", Action.Letters, 1.5f)),
            )
        }

        Page.More -> {
            listOf(
                symbolRow("~`|•√π÷×¶∆"),
                symbolRow("£€¥^°={}\\%"),
                listOf(Key("?123", Action.Symbols, 0f)) + symbolRow("©®™✓[]<>") + delete,
                bottom(Key("ABC", Action.Letters, 1.5f)),
            )
        }

        Page.Numbers -> {
            listOf(
                symbolRow("123-"),
                symbolRow("456") + Key(",", popup = listOf(":", "/")),
                symbolRow("789") + Key("", Action.Delete),
                symbolRow(".0") + Key("", Action.Space) + Key("", Action.Enter),
            )
        }

        Page.Phone -> {
            listOf(
                symbolRow("123-"),
                symbolRow("456+"),
                symbolRow("789") + Key("", Action.Delete),
                listOf(
                    Key("*", popup = listOf(",", ";")),
                    Key("0", popup = listOf("+")),
                ) +
                    Key("#") + Key("", Action.Enter),
            )
        }
    }
}
