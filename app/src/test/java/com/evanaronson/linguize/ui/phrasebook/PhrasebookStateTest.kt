package com.evanaronson.linguize.ui.phrasebook

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhraseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhrasebookStateTest {
    private val meVaBien = Phrase("1", "me va bien", "es bueno para mí", "Natural for plans that suit you.", "es", 30)
    private val esta = Phrase("2", "està", "esta", "The verb takes an accent.", "ca", 20)
    private val vale = Phrase("3", "vale", null, null, "es", 40)
    private val unknown = Phrase("4", "cheers", null, null, null, 10)
    private val all = listOf(vale, meVaBien, esta, unknown)

    @Test fun searchIgnoresCaseAndAccentsAcrossEveryField() {
        assertEquals(listOf(esta), filterPhrases(all, "ESTA"))
        assertEquals(listOf(esta), filterPhrases(all, "accent"))
        assertEquals(listOf(meVaBien), filterPhrases(all, "bueno mi"))
        assertEquals(listOf(meVaBien), filterPhrases(all, "  va   bien "))
        assertEquals(all, filterPhrases(all, "  "))
        assertEquals(emptyList<Phrase>(), filterPhrases(all, "nada"))
    }

    @Test fun sectionsFollowThePickerOrderThenUnknownNewestFirst() {
        val sections = groupByLanguage(all)
        assertEquals(listOf("ca", "es", null), sections.map { it.language })
        assertEquals(listOf(vale, meVaBien), sections[1].phrases)
        val other = Phrase("5", "danke", null, null, "de", 1)
        assertEquals(listOf("ca", "es", "de", null), groupByLanguage(all + other).map { it.language })
    }

    @Test fun exportIsPlainTextByLanguage() {
        val labels = PhraseTextLabels(
            title = "Phrasebook",
            language = { mapOf("ca" to "Catalan", "es" to "Spanish")[it] ?: "Other" },
            insteadOf = { "Instead of “$it”" },
        )
        val text = phrasebookText(groupByLanguage(listOf(meVaBien, esta)), labels)
        assertEquals(
            """
            Phrasebook

            Catalan

            està
            Instead of “esta”
            The verb takes an accent.

            Spanish

            me va bien
            Instead of “es bueno para mí”
            Natural for plans that suit you.

            """.trimIndent(),
            text,
        )
    }

    @Test fun aChangeOffersItsNewWordsWithItsReasonAndOrigin() {
        val origin = PhraseOrigin(language = { "es" }, app = { "com.whatsapp" }, sessionId = { "s1" })
        val draft = phraseCandidate(" es bueno para mí", "me va bien ", "More natural.", EditKind.Natural, origin)!!
        assertEquals("me va bien", draft.phrase)
        assertEquals("es bueno para mí", draft.replaced)
        assertEquals("More natural.", draft.meaning)
        assertEquals("es", draft.language)
        assertEquals(PhraseSource.Rewording, draft.source)
        assertEquals("com.whatsapp", draft.app)
        assertEquals("s1", draft.sessionId)
        assertEquals(PhraseSource.Fix, phraseCandidate("esta", "està", null, EditKind.Fix, origin)!!.source)
        assertNull(phraseCandidate("esta", "està", " ", EditKind.Fix, origin)!!.meaning)
        // An insertion has nothing it replaced.
        assertNull(phraseCandidate("", "que", null, EditKind.Fix, origin)!!.replaced)
    }

    @Test fun nothingToSaveForPunctuationSpacingDeletionsOrNoOrigin() {
        val origin = PhraseOrigin(language = { null })
        assertNull(phraseCandidate("hola", "", null, EditKind.Fix, origin))
        assertNull(phraseCandidate(".", ",", null, EditKind.Fix, origin))
        assertNull(phraseCandidate("  ", " ", null, EditKind.Fix, origin))
        assertNull(phraseCandidate("a", "b".repeat(301), null, EditKind.Natural, origin))
        assertNull(phraseCandidate("esta", "està", null, EditKind.Fix, null))
    }

    @Test fun theAnswerNamesTheLanguageOfALiveCheck() {
        assertEquals("es", answerLanguage("""{"status":"ok","language":"Spanish"}"""))
        assertEquals("ca", answerLanguage("""{"language":"Catalan (Valencian)"}"""))
        assertEquals("fr", answerLanguage("""{"language":"French"}"""))
        assertNull(answerLanguage("""{"language":"Elvish"}"""))
        assertNull(answerLanguage("""{"language":""}"""))
        assertNull(answerLanguage("not json"))
        assertNull(answerLanguage(null))
    }
}
