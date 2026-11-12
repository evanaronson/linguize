package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.ui.conjugations.VERB_WORD_LEVEL
import com.evanaronson.linguize.ui.conjugations.verbWordKey

/*
 * Lab, Conjugations page: where Your conjugations' links go. "Show all N verbs" opens the full
 * list (a cell's, from its page); a verb opens its row in Words, at the lexeme level, for its
 * counts and sentences.
 */

/** The full list of verbs in [language], or those of [cell]. */
internal fun conjugationVerbsScreen(language: String, cell: Cell?): Screen = Screen.ConjugationVerbs(language, cell?.token)

/** A verb's page in Words: the verb lexeme ("hablar/v"). */
internal fun conjugationVerbWordScreen(language: String, lemma: String): Screen =
    Screen.WordDetail(language, verbWordKey(lemma), VERB_WORD_LEVEL.token)
