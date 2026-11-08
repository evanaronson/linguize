package com.evanaronson.linguize.ui.teachme

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.SuggestionRecord
import org.junit.Assert.*
import org.junit.Test

class LessonTopicsTest {
    @Test fun findsTheSentenceAroundAChange() {
        val text = "Hola! Demà vaig poder venir. Fins aviat."
        val start = text.indexOf("vaig poder")
        assertEquals("Demà vaig poder venir.", sentenceAround(text, start, start + "vaig poder".length))
        assertEquals("Hola!", sentenceAround(text, 0, 4))
        assertEquals("Fins aviat.", sentenceAround(text, text.length - 3, text.length))
    }

    @Test fun lineBreaksEndSentences() {
        val text = "Bon dia\nem dic Anna\ni tu"
        val start = text.indexOf("dic")
        assertEquals("em dic Anna", sentenceAround(text, start, start + 3))
    }

    @Test fun anEmptyRangeStillHasASentence() {
        val text = "Vull anar. Vinc ara."
        assertEquals("Vinc ara.", sentenceAround(text, 12, 12))
    }

    @Test fun rangesOutsideTheTextHaveNoSentence() {
        assertNull(sentenceAround("abc", -1, 2))
        assertNull(sentenceAround("abc", 2, 1))
        assertNull(sentenceAround("abc", 0, 4))
        assertNull(sentenceAround("   ", 0, 1))
    }

    @Test fun aVeryLongSentenceIsAWindowAroundTheChange() {
        val text = "a ".repeat(400) + "CHANGE" + " b".repeat(400)
        val start = text.indexOf("CHANGE")
        val sentence = sentenceAround(text, start, start + 6, max = 100)!!
        assertTrue(sentence.length <= 100)
        assertTrue(sentence.contains("CHANGE"))
    }

    @Test fun languagesBecomeEnglishNames() {
        assertEquals("Catalan", lessonLanguage("ca"))
        assertEquals("Spanish", lessonLanguage("Spanish"))
        assertEquals("Portuguese", lessonLanguage("Portuguese"))
        assertNull(lessonLanguage(null))
        assertNull(lessonLanguage(" "))
    }

    @Test fun onlyTeachableChangesGetATopic() {
        assertNotNull(lessonTopic(" vaig ", "aniré", "Future", "ca", null))
        assertEquals("Catalan", lessonTopic("vaig", "aniré", "Future", "ca", null)!!.language)
        assertNull(lessonTopic("vaig", "aniré", null, "ca", null))
        assertNull(lessonTopic("vaig", "aniré", " ", "ca", null))
        assertNull(lessonTopic("same", "same", "Nothing", "ca", null))
    }

    @Test fun savedSuggestionsUseTheSavedSentenceOnlyWhenTheSpanStillFits() {
        val text = "Demà vaig poder venir."
        val start = text.indexOf("vaig poder")
        val row = record(start, start + "vaig poder".length, "vaig poder", "podré", "Future")
        val topic = savedLessonTopic(row, LessonSource("ca", text))!!
        assertEquals("Catalan", topic.language)
        assertEquals("Demà vaig poder venir.", topic.sentence)
        assertEquals("vaig poder", topic.from)

        val stale = savedLessonTopic(row.copy(start = 0, end = 4), LessonSource("ca", text))!!
        assertNull(stale.sentence)
        val outside = savedLessonTopic(row.copy(start = 50, end = 60), LessonSource(null, text))!!
        assertNull(outside.sentence)
        assertNull(outside.language)
        assertNull(savedLessonTopic(row.copy(why = null), LessonSource("ca", text)))
        assertNotNull(savedLessonTopic(row, null))
    }

    private fun record(start: Int, end: Int, from: String, to: String, why: String?) = SuggestionRecord(
        id = "s", sessionId = "x", deviceId = "d", createdAt = 0, updatedAt = 0, attempt = 0,
        kind = EditKind.Fix, start = start, end = end, fromText = from, toText = to, why = why,
        decision = Decision.Accepted, decidedAt = 0,
    )
}
