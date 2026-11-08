package com.evanaronson.linguize.core.opinion

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two answers to the same check, matched change by change. Most cases build the answers'
 * edits directly, so each matching rule is pinned exactly; the rest go through [interpret]
 * with answers as a model would write them.
 */
class OpinionsTest {
    private val both = listOf(EditKind.Fix, EditKind.Natural)

    private fun reviewed(original: String, vararg edits: Edit, withheld: Set<EditKind> = emptySet()) =
        CheckResult.Reviewed(Revision(original, edits.toList()), both, withheld = withheld)

    /** Replaces the [occurrence]th [from] in [original]. */
    private fun change(id: Int, original: String, from: String, to: String, kind: EditKind = EditKind.Fix, occurrence: Int = 0): Edit {
        var start = -1
        repeat(occurrence + 1) { start = original.indexOf(from, start + 1) }
        require(start >= 0) { "$from not in $original" }
        return Edit(id, kind, start, start + from.length, from, to, why = null)
    }

    private fun insert(id: Int, at: Int, text: String, kind: EditKind = EditKind.Fix) =
        Edit(id, kind, at, at, "", text, why = null)

    private fun compare(first: CheckResult.Reviewed, second: CheckResult.Reviewed) = Opinions.compare(first, second)

    private fun OpinionComparison.agreements() = items.map { it.agreement }

    @Test fun identicalAnswersAgreeOnEveryChange() {
        val text = "Com estas? Bé, i tu"
        val first = reviewed(text, change(0, text, "estas", "estàs"), insert(1, text.length, "?"))
        val second = reviewed(text, change(7, text, "estas", "estàs"), insert(3, text.length, "?"))
        val comparison = compare(first, second)

        assertEquals(listOf(Agreement.Agree, Agreement.Agree), comparison.agreements())
        assertTrue(comparison.allAgree)
        assertFalse(comparison.bothLookGood)
        assertEquals(listOf(0), comparison.items[0].firstIds)
        assertEquals(listOf(7), comparison.items[0].secondIds)
        assertEquals("estàs", comparison.items[0].first)
        assertEquals("estàs", comparison.items[0].second)
    }

    @Test fun bothFindingNothingIsReportedAsBothFine() {
        val comparison = compare(reviewed("Bon dia"), reviewed("Bon dia"))
        assertTrue(comparison.items.isEmpty())
        assertTrue(comparison.bothLookGood)
        assertFalse(comparison.allAgree)
    }

    @Test fun aChangeOnlyOneAnswerMakesIsMarkedAsTheirsAlone() {
        val text = "Com estas?"
        val comparison = compare(reviewed(text, change(0, text, "estas", "estàs")), reviewed(text))

        val item = comparison.items.single()
        assertEquals(Agreement.OnlyFirst, item.agreement)
        assertEquals("estàs", item.first)
        assertNull(item.second)
        assertEquals(EditKind.Fix, item.firstKind)
        assertNull(item.secondKind)
        assertFalse(comparison.firstLooksGood)
        assertTrue(comparison.secondLooksGood)
        assertFalse(comparison.bothLookGood)
    }

    @Test fun aChangeOnlyTheSecondMakesKeepsItsIds() {
        val text = "Vaig anar a la platja ahir"
        val comparison = compare(reviewed(text), reviewed(text, change(4, text, "ahir", "ahir.")))

        val item = comparison.items.single()
        assertEquals(Agreement.OnlySecond, item.agreement)
        assertEquals(emptyList<Int>(), item.firstIds)
        assertEquals(listOf(4), item.secondIds)
        assertEquals("ahir.", item.second)
        assertNull(comparison.forFirstEdit(4))
    }

    @Test fun differentReplacementsOfTheSameWordDisagree() {
        val text = "Com estas?"
        val comparison = compare(
            reviewed(text, change(0, text, "estas", "estàs")),
            reviewed(text, change(0, text, "estas", "està")),
        )
        val item = comparison.items.single()
        assertEquals(Agreement.Disagree, item.agreement)
        assertEquals("estas", item.from)
        assertEquals("estàs", item.first)
        assertEquals("està", item.second)
    }

    @Test fun differentInsertionsAtOnePlaceDisagree() {
        val text = "Hola com estàs"
        val comma = text.indexOf(" com")
        val comparison = compare(reviewed(text, insert(0, comma, ",")), reviewed(text, insert(0, comma, ";")))
        val item = comparison.items.single()
        assertEquals(Agreement.Disagree, item.agreement)
        assertEquals("", item.from)
        assertEquals(",", item.first)
        assertEquals(";", item.second)
    }

    @Test fun aWiderSpanWithTheSameEffectAgreesAndShowsOnlyTheChangedWord() {
        val text = "Anem pel cami vell"
        val comparison = compare(
            reviewed(text, change(0, text, "cami", "camí")),
            reviewed(text, change(0, text, "pel cami", "pel camí")),
        )
        val item = comparison.items.single()
        assertEquals(Agreement.Agree, item.agreement)
        assertEquals("pel cami", item.from)
        assertEquals(ShownChange("cami", "camí", "camí"), item.shown)
    }

    @Test fun oneAnswersTwoChangesAgreeWithTheOthersSingleChangeOverTheSameText() {
        val text = "hola com estàs"
        // The first splits capitalisation and the comma; the second makes them one change.
        val first = reviewed(text, change(0, text, "hola", "Hola"), insert(1, 4, ","))
        val second = reviewed(text, change(0, text, "hola", "Hola,"))
        val item = compare(first, second).items.single()

        assertEquals(Agreement.Agree, item.agreement)
        assertEquals(listOf(0, 1), item.firstIds)
        assertEquals(listOf(0), item.secondIds)
        assertEquals("Hola,", item.first)
        assertEquals("Hola,", item.second)
    }

    @Test fun anAdjacentExtraChangeStaysSeparateFromTheOneBothMade() {
        val text = "Anem pel cami"
        val first = reviewed(text, change(0, text, "cami", "camí"), insert(1, text.length, "."))
        val second = reviewed(text, change(0, text, "cami", "camí"))
        val comparison = compare(first, second)

        assertEquals(listOf(Agreement.Agree, Agreement.OnlyFirst), comparison.agreements())
        assertEquals(Agreement.Agree, comparison.forFirstEdit(0)?.agreement)
        assertEquals(Agreement.OnlyFirst, comparison.forFirstEdit(1)?.agreement)
    }

    @Test fun overlappingDifferentChangesBecomeOneDisagreement() {
        val text = "Ahir vaig poder anar"
        val first = reviewed(text, change(0, text, "vaig", "vam"), change(1, text, "anar", "venir"))
        val second = reviewed(text, change(0, text, "vaig poder anar", "podré anar"))
        val item = compare(first, second).items.single()

        assertEquals(Agreement.Disagree, item.agreement)
        assertEquals(listOf(0, 1), item.firstIds)
        assertEquals("vaig poder anar", item.from)
        assertEquals("vam poder venir", item.first)
        assertEquals("podré anar", item.second)
    }

    @Test fun repeatedIdenticalChangesPairUpByPlace() {
        val text = "a b c"
        val first = reviewed(text, insert(0, 1, "."), insert(1, 3, "."), insert(2, 5, "."))
        val second = reviewed(text, insert(0, 1, "."), insert(1, 5, "."))
        val comparison = compare(first, second)

        assertEquals(listOf(Agreement.Agree, Agreement.OnlyFirst, Agreement.Agree), comparison.agreements())
        assertEquals(listOf(1), comparison.items[1].firstIds)
        assertEquals(3, comparison.items[1].start)
    }

    @Test fun aFixInOneAnswerAndTheSameRewordingInTheOtherAgree() {
        val text = "Fins dema"
        val first = reviewed(text, change(0, text, "dema", "demà", EditKind.Fix))
        val second = reviewed(text, change(0, text, "dema", "demà", EditKind.Natural))
        val item = compare(first, second).items.single()

        assertEquals(Agreement.Agree, item.agreement)
        assertEquals(EditKind.Fix, item.firstKind)
        assertEquals(EditKind.Natural, item.secondKind)
    }

    @Test fun aFixAndADifferentRewordingAreNotForcedTogether() {
        val text = "Fins dema"
        val first = reviewed(text, change(0, text, "dema", "demà", EditKind.Fix))
        val second = reviewed(text, change(0, text, "Fins dema", "Fins aviat", EditKind.Natural))
        assertEquals(listOf(Agreement.OnlyFirst, Agreement.OnlySecond).toSet(), compare(first, second).agreements().toSet())
    }

    @Test fun rewordingsAreComparedWithRewordingsWhileFixesMatch() {
        val text = "Vull que vens dema"
        val fix = change(0, text, "dema", "demà")
        val first = reviewed(text, fix, change(1, text, "Vull que vens", "Vull que vinguis", EditKind.Natural))
        val second = reviewed(text, fix.copy(id = 5), change(6, text, "Vull que vens", "M'agradaria que vinguessis", EditKind.Natural))
        val comparison = compare(first, second)

        assertEquals(Agreement.Disagree, comparison.forFirstEdit(1)?.agreement)
        assertEquals(Agreement.Agree, comparison.forFirstEdit(0)?.agreement)
        val disagreement = comparison.forFirstEdit(1)!!
        assertEquals(ShownChange("Vull que vens", "Vull que vinguis", "M'agradaria que vinguessis"), disagreement.shown)
    }

    @Test fun composedAndDecomposedAccentsAreTheSameChange() {
        val text = "Com estas?"
        val decomposed = "estàs"
        val comparison = compare(
            reviewed(text, change(0, text, "estas", "estàs")),
            reviewed(text, change(0, text, "estas", decomposed)),
        )
        assertEquals(Agreement.Agree, comparison.items.single().agreement)
    }

    @Test fun itemsComeInTextOrderWhateverTheSection() {
        val text = "hola que tal estas"
        val first = reviewed(text, change(0, text, "estas", "estàs"), change(1, text, "hola", "Hola"))
        val second = reviewed(text, change(0, text, "que tal", "com va", EditKind.Natural), change(1, text, "estas", "estàs"))
        val starts = compare(first, second).items.map { it.start }
        assertEquals(starts.sorted(), starts)
        assertEquals(3, starts.size)
    }

    @Test fun withheldChangesMarkTheComparisonIncomplete() {
        val text = "Com estas?"
        val comparison = compare(reviewed(text, withheld = setOf(EditKind.Fix)), reviewed(text))
        assertTrue(comparison.incomplete)
        // A withheld answer did not find the text fine; it couldn't offer what it found.
        assertFalse(comparison.firstLooksGood)
        assertFalse(comparison.bothLookGood)
    }

    @Test fun answersAboutDifferentTextsCannotBeCompared() {
        val failure = runCatching { compare(reviewed("Hola"), reviewed("Adéu")) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun unclearAndWrongLanguageAnswersAreReportedAsSuch() {
        val first = reviewed("Com estas?")
        assertEquals(SecondAnswer.Unclear, Opinions.compare(first, CheckResult.Unclear))
        assertEquals(SecondAnswer.WrongLanguage("Spanish"),
            Opinions.compare(first, CheckResult.WrongLanguage("Catalan", "Spanish")))
        val compared: SecondAnswer = Opinions.compare(first, reviewed("Com estas?") as CheckResult)
        assertTrue(compared is SecondAnswer.Compared)
    }

    // Answers as a model writes them, through the same interpretation the card uses.

    private fun interpreted(original: String, verdict: Verdict) = interpret(original, verdict) as CheckResult.Reviewed

    @Test fun modelAnswersThatAgreeOnTheFixButDifferOnTheRewording() {
        val original = "Hola, com estas? Vull quedar dema."
        val gemini = interpreted(original, Verdict(
            Verdict.Status.Ok, language = "Catalan", hasErrors = true,
            corrected = "Hola, com estàs? Vull quedar demà.",
            fixes = listOf(VerdictChange("estas", "estàs", confidence = 9, impact = 6),
                VerdictChange("dema", "demà", confidence = 9, impact = 6)),
            moreNatural = true, natural = "Hola, com estàs? Vull que quedem demà.",
            naturalChanges = listOf(VerdictChange("Vull quedar", "Vull que quedem", confidence = 6, impact = 3)),
        ))
        val openai = interpreted(original, Verdict(
            Verdict.Status.Ok, language = "Catalan", hasErrors = true,
            corrected = "Hola, com estàs? Vull quedar demà.",
            fixes = listOf(VerdictChange("estas", "estàs", confidence = 10, impact = 6),
                VerdictChange("dema", "demà", confidence = 10, impact = 6)),
        ))
        val comparison = compare(gemini, openai)

        val fixes = gemini.revision.edits(EditKind.Fix)
        assertEquals(2, fixes.size)
        fixes.forEach { assertEquals(Agreement.Agree, comparison.forFirstEdit(it.id)?.agreement) }
        val rewording = gemini.revision.edits(EditKind.Natural).single()
        val item = comparison.forFirstEdit(rewording.id)!!
        assertEquals(Agreement.OnlyFirst, item.agreement)
        assertEquals(2, comparison.count(Agreement.Agree))
        assertEquals(1, comparison.count(Agreement.OnlyFirst))
        assertEquals(0, comparison.count(Agreement.OnlySecond))
    }

    @Test fun modelAnswersThatBothFindTheTextFine() {
        val original = "Bon dia, com estàs?"
        val fine = Verdict(Verdict.Status.Ok, language = "Catalan", meaning = "Good morning, how are you?")
        val comparison = compare(interpreted(original, fine), interpreted(original, fine.copy(meaning = "Morning! How are you?")))
        assertTrue(comparison.bothLookGood)
        assertTrue(comparison.items.isEmpty())
    }

    @Test fun modelAnswersThatDisagreeOnAWord() {
        val original = "Ens veiem el dilluns que be"
        val a = interpreted(original, Verdict(Verdict.Status.Ok, language = "Catalan", hasErrors = true,
            corrected = "Ens veiem el dilluns que ve", fixes = listOf(VerdictChange("be", "ve", confidence = 8, impact = 7))))
        val b = interpreted(original, Verdict(Verdict.Status.Ok, language = "Catalan", hasErrors = true,
            corrected = "Ens veiem el dilluns que bé", fixes = listOf(VerdictChange("be", "bé", confidence = 4, impact = 5))))
        val item = compare(a, b).items.single()
        assertEquals(Agreement.Disagree, item.agreement)
        assertEquals(ShownChange("be", "ve", "bé"), item.shown)
    }

    @Test fun sharedContextIsTrimmedByWholeWordsOnly() {
        assertEquals(ShownChange("cami", "camí", null), Opinions.shared("el cami", "el camí", null))
        assertEquals(ShownChange("", " c", null), Opinions.shared("a b", "a b c", null))
        assertEquals(ShownChange("hola", "Hola,", "Hola"), Opinions.shared("hola", "Hola,", "Hola"))
        assertEquals(ShownChange("", ",", ";"), Opinions.shared("", ",", ";"))
    }
}
