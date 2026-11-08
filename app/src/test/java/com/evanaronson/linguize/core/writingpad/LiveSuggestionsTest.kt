package com.evanaronson.linguize.core.writingpad

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSuggestionsTest {
    private fun fix(text: String, from: String, to: String, key: Long = 1, occurrence: Int = 0): LiveSuggestion {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(from, at + 1) }
        require(at >= 0) { "$from not in $text" }
        return LiveSuggestion(key, EditKind.Fix, at, at + from.length, from, to, "why")
    }

    private fun insertion(at: Int, text: String, key: Long, kind: EditKind = EditKind.Fix) =
        LiveSuggestion(key, kind, at, at, "", text, null)

    private fun remap(old: String, new: String, vararg items: LiveSuggestion, sentenceScope: Boolean = true) =
        LiveSuggestions.remap(old, new, items.toList(), sentenceScope)

    /** Every surviving suggestion still points at its own text. */
    private fun assertAnchored(text: String, items: List<LiveSuggestion>) =
        items.forEach { assertEquals(it.from, text.substring(it.start, it.end)) }

    private val two = "Com estas? Vull anar a la platja."

    @Test fun typingAfterASuggestionLeavesItInPlace() {
        val estas = fix(two, "estas", "estàs")
        val new = "$two Fins demà."
        val moved = remap(two, new, estas)
        assertEquals(listOf(estas), moved)
    }

    @Test fun typingBeforeASuggestionShiftsIt() {
        val platja = fix(two, "platja", "platja", key = 2).copy(replacement = "platja!")
        val new = "Hola. $two"
        val moved = remap(two, new, platja).single()
        assertEquals(platja.start + 6, moved.start)
        assertAnchored(new, listOf(moved))
    }

    @Test fun typingInsideASuggestionDropsIt() {
        val estas = fix(two, "estas", "estàs")
        assertEquals(emptyList<LiveSuggestion>(), remap(two, two.replace("estas", "esttas"), estas))
    }

    @Test fun deletingASuggestionDropsIt() {
        val estas = fix(two, "estas", "estàs")
        assertEquals(emptyList<LiveSuggestion>(), remap(two, two.replace("estas", ""), estas))
    }

    @Test fun aLetterRightAgainstASuggestionDropsItButASpaceDoesNot() {
        val text = "Hola cami"
        val cami = fix(text, "cami", "camí")
        assertEquals(emptyList<LiveSuggestion>(), remap(text, "Hola camis", cami, sentenceScope = false))
        assertEquals(emptyList<LiveSuggestion>(), remap(text, "Hola xcami", cami, sentenceScope = false))
        assertEquals(listOf(cami), remap(text, "Hola cami ", cami))
        assertEquals(listOf(cami), remap(text, "Hola cami,", cami))
        // Deleting the letter before it changes the word too.
        assertEquals(emptyList<LiveSuggestion>(),
            remap("Hola acami", "Hola cami", fix("Hola acami", "cami", "camí"), sentenceScope = false))
    }

    @Test fun anInsertionIsDroppedWhenTypingLandsOnItsPlace() {
        val text = "Hola com estas"
        val comma = insertion(4, ",", key = 3)
        assertEquals(emptyList<LiveSuggestion>(), remap(text, "Hola! com estas", comma, sentenceScope = false))
        assertEquals(emptyList<LiveSuggestion>(), remap(text, "Hol com estas", comma, sentenceScope = false))
        // Typing elsewhere moves it.
        val moved = remap(text, "Ei, Hola com estas", comma, sentenceScope = false).single()
        assertEquals(8, moved.start)
    }

    @Test fun editingTheSameSentenceDropsItsSuggestionsWithSentenceScope() {
        val estas = fix(two, "estas", "estàs")
        val new = two.replace("Com", "Com que")
        assertEquals(emptyList<LiveSuggestion>(), remap(two, new, estas, sentenceScope = true))
        // Without sentence scope, the word itself is untouched and stays.
        val kept = remap(two, new, estas, sentenceScope = false).single()
        assertAnchored(new, listOf(kept))
    }

    @Test fun editingAnotherSentenceKeepsAndShiftsWithSentenceScope() {
        val platja = fix(two, "platja", "platja.").copy(replacement = "mar")
        val new = two.replace("Com estas?", "Com estàs, amic?")
        val moved = remap(two, new, platja).single()
        assertAnchored(new, listOf(moved))
        assertEquals(platja.start + 6, moved.start)
    }

    @Test fun appendingToTheLastSentenceKeepsItsSuggestions() {
        val text = "Vull anar a la platja"
        val vull = fix(text, "Vull", "Vull")
        assertEquals(listOf(vull), remap(text, "$text demà", vull))
    }

    @Test fun aPasteThatChangesTwoPlacesKeepsWhatIsBetweenThemThroughAlignment() {
        val old = "Hola amic. Avui fa sol i anem a la platja amb la Maria. Fins aviat."
        val new = "Hola amics. Avui fa sol i anem a la platja amb la Maria. Fins demà."
        val platja = fix(old, "platja", "platja").copy(replacement = "mar", key = 9)
        val moved = remap(old, new, platja).single()
        assertAnchored(new, listOf(moved))
        assertEquals(platja.start + 1, moved.start)
    }

    @Test fun aSuggestionWhoseTextIsNoLongerThereIsDropped() {
        val wrong = LiveSuggestion(1, EditKind.Fix, 0, 3, "xyz", "abc", null)
        assertEquals(emptyList<LiveSuggestion>(), remap(two, "$two!", wrong))
    }

    @Test fun manyKeystrokesInARowKeepTheSuggestionAnchored() {
        var text = two
        var items = listOf(fix(two, "platja", "platja").copy(replacement = "mar"), fix(two, "estas", "estàs", key = 2))
        for (typed in " Fins aviat, amic.") {
            val next = text + typed
            items = LiveSuggestions.remap(text, next, items, sentenceScope = true)
            text = next
        }
        assertEquals(2, items.size)
        assertAnchored(text, items)
        // Editing the second sentence drops only its own suggestion.
        val withEdit = text.replace("Vull", "Volem")
        items = LiveSuggestions.remap(text, withEdit, items, sentenceScope = true)
        assertEquals(listOf(2L), items.map { it.key })
        assertAnchored(withEdit, items)
        // A new sentence in front of the first one leaves it as it was.
        val prepended = LiveSuggestions.remap(withEdit, "Ei! $withEdit", items, sentenceScope = true).single()
        assertEquals(items.single().start + 4, prepended.start)
        // A word in front of it edits it.
        assertEquals(emptyList<LiveSuggestion>(), LiveSuggestions.remap(withEdit, "Doncs, $withEdit", items, sentenceScope = true))
    }

    // Accepting

    @Test fun acceptingAFixShiftsLaterSuggestionsAndKeepsAnAdjacentInsertion() {
        val text = "Hola cami com estas"
        val cami = fix(text, "cami", "camí", key = 1)
        val comma = insertion(text.indexOf("cami") + 4, ",", key = 2)
        val estas = fix(text, "estas", "estàs", key = 3)
        val after = text.replaceRange(cami.start, cami.end, "camí")
        val left = LiveSuggestions.afterAccepting(text, after, cami, listOf(comma, estas))
        assertEquals(listOf(2L, 3L), left.map { it.key })
        assertAnchored(after, left)
    }

    @Test fun acceptingALongerReplacementShiftsWhatFollows() {
        val text = "a b c"
        val b = LiveSuggestion(1, EditKind.Fix, 2, 3, "b", "bbb", null)
        val c = LiveSuggestion(2, EditKind.Fix, 4, 5, "c", "C", null)
        val after = "a bbb c"
        val left = LiveSuggestions.afterAccepting(text, after, b, listOf(c)).single()
        assertEquals(6, left.start)
    }

    @Test fun acceptingAFixKeepsTheRewordingThatContainsItOnTheCorrectedWords() {
        val text = "Vull anar a la platja demà al mati"
        val mati = fix(text, "mati", "matí", key = 1)
        val start = text.indexOf("demà")
        val rewording = LiveSuggestion(2, EditKind.Natural, start, text.length, text.substring(start), "demà pel matí", null, linked = setOf(1))
        val fixWithLink = mati.copy(linked = setOf(2))
        val after = text.replace("mati", "matí")
        val left = LiveSuggestions.afterAccepting(text, after, fixWithLink, listOf(rewording)).single()
        assertEquals("demà al matí", left.from)
        assertAnchored(after, listOf(left))
        assertTrue(1L !in left.linked)
    }

    @Test fun acceptingARewordingRetiresTheFixesItTouches() {
        val text = "Vull anar, a la platja demà al mati"
        val mati = fix(text, "mati", "matí", key = 1).copy(linked = setOf(2))
        val start = text.indexOf("demà")
        val rewording = LiveSuggestion(2, EditKind.Natural, start, text.length, text.substring(start), "demà pel matí", null, linked = setOf(1, 3))
        // A fix right at the rewording's edge that the rewording includes.
        val edge = LiveSuggestion(3, EditKind.Fix, start - 1, start, " ", "  ", null, linked = setOf(2))
        val comma = fix(text, ",", "", key = 4)
        val after = text.replaceRange(start, text.length, "demà pel matí")
        val left = LiveSuggestions.afterAccepting(text, after, rewording, listOf(mati, edge, comma))
        assertEquals(listOf(4L), left.map { it.key })
        assertAnchored(after, left)
    }

    // From a check

    @Test fun suggestionsComeFromTheEditEngineWithLinksBetweenRewordingsAndFixes() {
        val text = "Bon dia, com estas? Vull anar a la platja."
        val verdict = Verdict(
            status = Verdict.Status.Ok,
            language = "Catalan",
            hasErrors = true,
            corrected = "Bon dia, com estàs? Vull anar a la platja.",
            fixes = listOf(VerdictChange("estas", "estàs", "Accent on the verb.")),
            moreNatural = true,
            natural = "Bon dia, com estàs? M'agradaria anar a la platja.",
            naturalChanges = listOf(VerdictChange("Vull", "M'agradaria", "Softer.")),
        )
        val result = interpret(text, verdict) as CheckResult.Reviewed
        var key = 100L
        val live = LiveSuggestions.of(text, result) { key++ }
        assertAnchored(text, live)
        val estas = live.single { it.kind == EditKind.Fix }
        assertEquals("estàs", estas.replacement)
        assertEquals("Accent on the verb.", estas.why)
        val rewording = live.single { it.kind == EditKind.Natural }
        assertTrue(rewording.replacement.contains("agradaria"))
        assertEquals(live.map { it.key }.toSet().size, live.size)
        assertNull(live.firstOrNull { it.from == it.replacement })
    }
}
