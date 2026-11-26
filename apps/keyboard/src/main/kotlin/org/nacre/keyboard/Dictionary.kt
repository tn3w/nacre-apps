package org.nacre.keyboard

import android.content.Context
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

data class Suggestions(
    val typed: String = "",
    val words: List<String> = emptyList(),
    val correction: String? = null,
)

fun fold(word: String): String =
    Normalizer
        .normalize(word.lowercase(), Normalizer.Form.NFD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

fun matchCase(
    word: String,
    typed: String,
) = when {
    typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> {
        word
            .uppercase()
    }

    typed.firstOrNull()?.isUpperCase() == true -> {
        word.replaceFirstChar(
            Char::uppercaseChar,
        )
    }

    else -> {
        word
    }
}

class Dictionary(
    language: Language,
    private val words: List<String>,
    private val frequencies: IntArray,
) {
    private val folded = words.map(::fold)
    private val known = words.toHashSet()
    private val positions =
        language.rows
            .flatMapIndexed { row, chars ->
                val offset = (language.rows[0].length - chars.length) / 2f
                chars.mapIndexed { column, char -> char to (row to column + offset) }
            }.toMap()

    private fun near(
        first: Char,
        second: Char,
    ): Boolean {
        val a = positions[first] ?: return false
        val b = positions[second] ?: return false
        return abs(a.first - b.first) <= 1 && abs(a.second - b.second) <= 1.1f
    }

    fun contains(
        word: String,
        personal: Map<String, Int>,
    ) = word in known || word.lowercase() in known || word in personal ||
        word.lowercase() in personal

    fun suggest(
        typed: String,
        personal: Map<String, Int>,
        autocorrect: Boolean,
    ): Suggestions {
        val key = fold(typed)
        val limit =
            when {
                key.length <= 2 -> 0.0
                key.length <= 4 -> 1.0
                else -> 2.0
            }
        val scores = HashMap<String, Double>()

        fun consider(
            word: String,
            foldedWord: String,
            frequency: Int,
        ) {
            val score = score(key, foldedWord, frequency, limit) ?: return
            if (score > (scores[word] ?: Double.NEGATIVE_INFINITY)) scores[word] = score
        }
        words.indices.forEach { consider(words[it], folded[it], frequencies[it]) }
        personal.forEach { (word, count) ->
            consider(
                word,
                fold(word),
                min(
                    255,
                    150 + count * 10,
                ),
            )
        }

        val ranked =
            scores.entries
                .sortedByDescending { it.value }
                .map {
                    matchCase(
                        it.key,
                        typed,
                    )
                }.filter { it != typed }
                .distinct()
        val valid = contains(typed, personal)
        val best = scores.maxByOrNull { it.value }
        val confident =
            best != null && best.value >= CONFIDENCE &&
                fold(best.key).length <= key.length + 1
        val correction =
            ranked
                .firstOrNull()
                .takeIf {
                    autocorrect && !valid && confident &&
                        typed.all { it.isLetter() || it in "'-" }
                }
        return Suggestions(typed, ranked.take(3), correction)
    }

    private fun score(
        typed: String,
        word: String,
        frequency: Int,
        limit: Double,
    ): Double? {
        if (word == typed) return 1000.0 + frequency
        if (word.length > typed.length && word.startsWith(typed)) {
            return frequency - 12.0 * (word.length - typed.length) - 30
        }
        if (abs(word.length - typed.length) > limit) return null
        val distance = distance(typed, word, limit, ::near) ?: return null
        return frequency - 70 * distance
    }

    companion object {
        private const val CONFIDENCE = 40.0
        private val cache = HashMap<Language, Dictionary>()

        fun of(
            context: Context,
            language: Language,
        ) = synchronized(cache) {
            cache.getOrPut(language) { load(context, language) }
        }

        private fun load(
            context: Context,
            language: Language,
        ): Dictionary {
            val words = ArrayList<String>(60_000)
            val frequencies = ArrayList<Int>(60_000)
            context.assets.open("${language.code}.txt").bufferedReader().forEachLine {
                val (word, frequency) = it.split('\t')
                words += word
                frequencies += frequency.toInt()
            }
            return Dictionary(language, words, frequencies.toIntArray())
        }
    }
}

private fun distance(
    a: String,
    b: String,
    limit: Double,
    near: (Char, Char) -> Boolean,
): Double? {
    var previous = DoubleArray(b.length + 1) { it.toDouble() }
    var before = previous
    for (i in 1..a.length) {
        val row = DoubleArray(b.length + 1)
        row[0] = i.toDouble()
        for (j in 1..b.length) {
            val x = a[i - 1]
            val y = b[j - 1]
            val swap =
                when {
                    x == y -> 0.0
                    near(x, y) -> 0.6
                    else -> 1.0
                }
            row[j] = minOf(previous[j - 1] + swap, previous[j] + 1, row[j - 1] + gap(y))
            if (i > 1 && j > 1 && x == b[j - 2] && a[i - 2] == y) {
                row[j] = min(row[j], before[j - 2] + 1)
            }
        }
        if (row.min() > limit) return null
        before = previous
        previous = row
    }
    return previous[b.length].takeIf { it <= limit }
}

private fun gap(char: Char) = if (char == '\'' || char == '-') 0.3 else 1.0
