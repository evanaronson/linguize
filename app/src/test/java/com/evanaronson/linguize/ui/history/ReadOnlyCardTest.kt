package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.history.Replayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A past card is shown as it ended, with nothing on it that would ask the model again. */
class ReadOnlyCardTest {
    private val text = "Ell va venir ahir"
    private val verdict = Verdict(
        status = Verdict.Status.Ok,
        assumptions = listOf(Assumption(about = "Ell", assumed = "a man", words = "Ell", alternatives = listOf("a boy"))),
        hasErrors = true,
        corrected = "Ell va venir ahir.",
    )

    @Test
    fun assumptionsLoseTheirAlternativesAndTheRestIsKept() {
        val reviewed = interpret(text, verdict) as CheckResult.Reviewed
        val settled = listOf(Settled("Ell", "a man"))
        val card = readOnlyCard(Replayed(reviewed, settled))
        val shown = card.result as CheckResult.Reviewed
        assertEquals(listOf(Assumption(about = "Ell", assumed = "a man", words = "Ell")), shown.assumptions)
        assertEquals(reviewed.revision, shown.revision)
        assertEquals(reviewed.meaning, shown.meaning)
        assertEquals(settled, card.settled)
    }

    @Test
    fun aResultWithNothingToReviewIsShownAsItIs() {
        val wrong = CheckResult.WrongLanguage("Catalan", "Spanish")
        assertSame(wrong, readOnlyCard(Replayed(wrong, emptyList())).result)
        assertSame(CheckResult.Unclear, readOnlyCard(Replayed(CheckResult.Unclear, emptyList())).result)
    }
}
