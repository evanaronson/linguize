package com.evanaronson.linguize.core.writingpad

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadEngineTest {
    private val idle = 1000L
    private val text = "Bon dia, com estas? Vull anar a la platja."

    /** What a check of [checked] returns: a fix of "estas" and the meaning. */
    private fun reviewed(checked: String, meaning: String = "Good morning, how are you?"): CheckResult =
        interpret(checked, Verdict(
            status = Verdict.Status.Ok,
            language = "Catalan",
            meaning = meaning,
            hasErrors = true,
            corrected = checked.replace("estas", "estàs"),
            fixes = listOf(VerdictChange("estas", "estàs", "Accent on the verb.")),
        ))

    private fun anchored(engine: PadEngine) =
        engine.suggestions.forEach { assertEquals(it.from, engine.text.substring(it.start, it.end)) }

    @Test fun anOpenedDraftIsCheckedRightAway() {
        val engine = PadEngine(text, idle)
        val start = engine.due(0)!!
        assertEquals(text, start.request.text)
    }

    @Test fun anAnswerLandsOnTextTypedWhileItRan() {
        val engine = PadEngine(text, idle)
        val start = engine.due(0)!!
        val typed = "$text Fins aviat."
        engine.edit(typed, 10)
        assertTrue(engine.received(start.request, reviewed(text)))
        val estas = engine.suggestions.single()
        assertEquals(text.indexOf("estas"), estas.start)
        anchored(engine)
        // The meaning is for the older text until the next check.
        assertFalse(engine.meaningIsCurrent)
        assertEquals("Good morning, how are you?", engine.meaning?.text)
        assertTrue(engine.schedule.pending)
    }

    @Test fun anAnswerIsDroppedWhereTheWriterAlreadyChangedTheSentence() {
        val engine = PadEngine(text, idle)
        val start = engine.due(0)!!
        engine.edit(text.replace("com estas", "com estas tu"), 10)
        assertTrue(engine.received(start.request, reviewed(text)))
        assertEquals(emptyList<LiveSuggestion>(), engine.suggestions)
    }

    @Test fun aStaleAnswerChangesNothing() {
        val engine = PadEngine(text, idle)
        val first = engine.due(0)!!
        val newer = "$text Fins aviat."
        engine.edit(newer, 10)
        val second = engine.due(10 + idle)!!
        assertEquals(first.request, second.cancelled)
        assertFalse(engine.received(first.request, reviewed(text, meaning = "old")))
        assertEquals(emptyList<LiveSuggestion>(), engine.suggestions)
        assertNull(engine.meaning)
        assertTrue(engine.received(second.request, reviewed(newer, meaning = "new")))
        assertEquals("new", engine.meaning?.text)
        assertTrue(engine.meaningIsCurrent)
    }

    @Test fun acceptingPutsTheChangeInAndNeedsNoNewCheck() {
        val engine = PadEngine(text, idle)
        engine.received(engine.due(0)!!.request, reviewed(text))
        val key = engine.suggestions.single().key
        val cursor = engine.accept(key, 50)
        assertEquals(text.replace("estas", "estàs"), engine.text)
        assertEquals(text.indexOf("estas") + "estàs".length, cursor)
        assertEquals(emptyList<LiveSuggestion>(), engine.suggestions)
        assertFalse(engine.schedule.pending)
        assertTrue(engine.meaningIsCurrent)
        assertNull(engine.accept(key, 60))
    }

    @Test fun aDismissedChangeStaysHiddenWhenTheSentenceIsCheckedAgain() {
        val engine = PadEngine(text, idle)
        engine.received(engine.due(0)!!.request, reviewed(text))
        engine.dismiss(engine.suggestions.single().key)
        assertEquals(0, engine.count(EditKind.Fix))
        // A later sentence changes; the next answer offers the same fix again.
        val longer = "$text Fins aviat."
        engine.edit(longer, 100)
        val again = engine.due(100 + idle)!!
        engine.received(again.request, reviewed(longer))
        assertEquals(emptyList<LiveSuggestion>(), engine.suggestions)
        // In a different sentence it is offered.
        val other = "$longer I tu com estas?"
        engine.edit(other, 200)
        engine.received(engine.due(200 + idle)!!.request, reviewed(other))
        assertEquals(1, engine.suggestions.size)
        assertTrue(engine.suggestions.single().start > longer.length)
    }

    @Test fun unclearAndWrongLanguageClearTheSuggestions() {
        val engine = PadEngine(text, idle)
        engine.received(engine.due(0)!!.request, reviewed(text))
        engine.edit("$text!", 10)
        engine.received(engine.due(10 + idle)!!.request, CheckResult.Unclear)
        assertEquals(PadEngine.Outcome.Unclear, engine.outcome)
        assertEquals(emptyList<LiveSuggestion>(), engine.suggestions)
        engine.edit("$text!!", 20)
        engine.received(engine.due(20 + idle)!!.request, CheckResult.WrongLanguage("Catalan", "Spanish"))
        assertEquals(PadEngine.Outcome.WrongLanguage("Catalan", "Spanish"), engine.outcome)
    }

    @Test fun suggestionsUnderTheCursorPutFixesFirst() {
        val engine = PadEngine(text, idle)
        engine.received(engine.due(0)!!.request, reviewed(text))
        val at = text.indexOf("estas") + 2
        assertEquals(listOf("estas"), engine.at(at).map { it.from })
        assertEquals(emptyList<LiveSuggestion>(), engine.at(0))
    }

    @Test fun tooLongTextIsNeverSent() {
        val engine = PadEngine("a".repeat(50), idle, maxChars = 40)
        assertTrue(engine.tooLong)
        assertNull(engine.due(10_000))
        engine.edit("a".repeat(30), 10_000)
        assertFalse(engine.tooLong)
        assertEquals("a".repeat(30), engine.due(10_000 + idle)!!.request.text)
    }

    @Test fun failuresWaitForRetry() {
        val engine = PadEngine(text, idle)
        val first = engine.due(0)!!
        assertTrue(engine.failed(first.request))
        assertNull(engine.due(idle * 10))
        assertEquals(text, engine.retry(idle * 10)!!.request.text)
    }
}
