package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.corpus.WriterGaps
import com.evanaronson.linguize.history.patterns.Basis
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.history.patterns.PatternRules
import com.evanaronson.linguize.ui.phrasebook.answerLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab, language-generic: History, patterns, phrases and gaps for languages other than Spanish and Catalan. */
class LanguageGenericHistoryTest {
    @Test fun anyDetectedLanguageFilesUnderItsCurrentCode() {
        assertEquals("fr", languageCode("French"))
        assertEquals("he", languageCode("Hebrew"))
        assertEquals("he", languageCode("iw"))
        assertEquals("id", languageCode("Indonesian"))
        assertEquals("es", languageCode("Castilian"))
        assertEquals("fr", answerLanguage("""{"language":"French"}"""))
        assertEquals("es", answerLanguage("""{"language":"Spanish (Mexican)"}"""))
        assertNull(answerLanguage("""{"language":""}"""))
    }

    @Test fun patternsInOtherLanguagesUseOnlyTheRulesThatHoldEverywhere() {
        // Spanish words: the word rules apply.
        assertEquals(PatternKind.Gender, PatternRules.classify("la problema", "el problema", null, "es")?.kind)
        // French: no Spanish or Catalan word lists; spelling closeness or the reason decides.
        assertFalse(PatternRules.classify("la problème", "le problème", null, "fr")?.kind in setOf(PatternKind.Gender, PatternKind.Articles))
        assertEquals(PatternKind.Accents to Basis.Exact, PatternRules.classify("a bientot", "à bientôt", null, "fr")?.let { it.kind to it.basis })
        assertEquals(PatternKind.Capitals, PatternRules.classify("berlin", "Berlin", null, "de")?.kind)
        assertEquals(PatternKind.Punctuation, PatternRules.classify("Hallo Anna", "Hallo, Anna", null, "de")?.kind)
    }

    @Test fun englishWordsAreGapsExceptInEnglish() {
        assertTrue(WriterGaps.isEnglishGap("the", "fr"))
        assertTrue(WriterGaps.isEnglishGap("the", null))
        assertFalse(WriterGaps.isEnglishGap("the", "en"))
    }
}
