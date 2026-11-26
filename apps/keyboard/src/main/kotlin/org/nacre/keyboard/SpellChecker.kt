package org.nacre.keyboard

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

class SpellChecker : SpellCheckerService() {
    override fun createSession() =
        object : Session() {
            override fun onCreate() = Unit

            override fun onGetSuggestions(
                info: TextInfo,
                limit: Int,
            ): SuggestionsInfo {
                val word = info.text.trim()
                val language =
                    Language.of(locale.substringBefore('_'))
                        ?: Store.languages.value.first()
                val dictionary = Dictionary.of(this@SpellChecker, language)
                if (word.none(Char::isLetter) ||
                    dictionary.contains(word, Store.trusted)
                ) {
                    return SuggestionsInfo(
                        SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY,
                        emptyArray(),
                    )
                }
                val suggestions =
                    dictionary
                        .suggest(
                            word,
                            Store.trusted,
                            autocorrect = false,
                        ).words
                return SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                        SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS,
                    suggestions.take(limit.coerceAtLeast(0)).toTypedArray(),
                )
            }
        }
}
