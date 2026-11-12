package com.evanaronson.linguize.ui.words

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.words.WordCounts
import com.evanaronson.linguize.core.words.WordLevel

/** The short, localized part-of-speech label: noun, verb, adj, art… */
@get:StringRes
internal val Pos.labelResource: Int
    get() = when (this) {
        Pos.Noun -> R.string.lab_words_pos_noun
        Pos.Verb -> R.string.lab_words_pos_verb
        Pos.Adjective -> R.string.lab_words_pos_adjective
        Pos.Adverb -> R.string.lab_words_pos_adverb
        Pos.Pronoun -> R.string.lab_words_pos_pronoun
        Pos.Determiner -> R.string.lab_words_pos_determiner
        Pos.Article -> R.string.lab_words_pos_article
        Pos.Preposition -> R.string.lab_words_pos_preposition
        Pos.Conjunction -> R.string.lab_words_pos_conjunction
        Pos.Numeral -> R.string.lab_words_pos_numeral
        Pos.Interjection -> R.string.lab_words_pos_interjection
        Pos.Other -> R.string.lab_words_pos_other
        Pos.Unknown -> R.string.lab_words_pos_unknown
    }

/** "el (art)"; just the lemma when nothing is known about its part of speech. */
@Composable
internal fun lexemeTitle(lexeme: Lexeme): String =
    if (lexeme.pos == Pos.Unknown) lexeme.lemma
    else stringResource(R.string.lab_words_lexeme, lexeme.lemma, stringResource(lexeme.pos.labelResource))

@Composable
internal fun levelLabel(level: WordLevel): String = stringResource(
    when (level) {
        WordLevel.Forms -> R.string.lab_words_by_form
        WordLevel.Lexemes -> R.string.lab_words_by_lexeme
        WordLevel.Lemmas -> R.string.lab_words_by_lemma
    },
)

/**
 * A list row's use, in two lines: "12 right, 3 wrong" and "80% right, last Nov 3"; "Not yet"
 * and no second line for a deck row never reached. Right and wrong never overlap, so either
 * can be the larger, and the share right says which way it leans.
 */
internal fun rowUse(resources: Resources, counts: WordCounts?, day: (Long) -> String): Pair<String, String?> {
    if (counts == null) return resources.getString(R.string.lab_vocab_gaps_not_yet) to null
    val status = resources.getString(R.string.lab_words_row_right_wrong, counts.right, counts.wrong)
    val last = counts.accuracy?.let { resources.getString(R.string.lab_words_row_accuracy_last, it, day(counts.lastAt)) }
        ?: resources.getString(R.string.lab_vocab_gaps_last_used, day(counts.lastAt))
    return status to last
}
