package com.evanaronson.linguize.ui.words

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
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
