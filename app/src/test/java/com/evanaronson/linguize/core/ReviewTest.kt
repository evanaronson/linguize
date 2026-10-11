package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private fun reviewed(original: String, verdict: Verdict) =
        interpret(original, verdict) as CheckResult.Reviewed

    private fun Revision.replacements(kind: EditKind) = remaining(kind).map { it.replacement }

    @Test
    fun everyPunctuationMarkAndWordIsItsOwnFix() {
        val original = "hola q tal bb estas bien te encanta esta musica no"
        val corrected = "hola, q tal, bb? Estás bien? Te encanta esta música, no?"
        val revision = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected)).revision

        assertEquals(
            listOf(",", ",", "?", "Estás", "?", "Te", "música", ",", "?"),
            revision.replacements(EditKind.Fix),
        )
        assertEquals(corrected, revision.preview(EditKind.Fix).text)
    }

    @Test
    fun reasonsComeFromTheModelsList() {
        val revision = reviewed(
            "Com estas?",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Com estàs?",
                fixes = listOf(VerdictChange("estas", "estàs", why = "Missing accent")),
            ),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("estas", fix.from)
        assertEquals("Missing accent", fix.why)
    }

    @Test
    fun fixesCanBeAcceptedInAnyOrderAndUndone() {
        val original = "unes tomàquets molt bo"
        val revision = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "uns tomàquets molt bons"),
        ).revision
        val (first, second) = revision.remaining(EditKind.Fix)

        val secondOnly = revision.accept(second.id)
        assertEquals("unes tomàquets molt bons", secondOnly.workingText)
        assertEquals(listOf("uns"), secondOnly.replacements(EditKind.Fix))

        val both = secondOnly.accept(first.id)
        assertEquals("uns tomàquets molt bons", both.workingText)
        assertTrue(both.remaining(EditKind.Fix).isEmpty())

        assertEquals("unes tomàquets molt bons", both.undo().workingText)
        assertEquals(original, both.undo().undo().workingText)
    }

    @Test
    fun rewordingLeavesUnrelatedErrorsToTheFixes() {
        val original = "Voy a tomar una ducha y te llamo despues"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Voy a tomar una ducha y te llamo después",
                moreNatural = true,
                natural = "Me voy a duchar y te llamo después",
                naturalChanges = listOf(VerdictChange("Voy a tomar una ducha", "Me voy a duchar", why = "More usual")),
            ),
        ).revision

        val natural = revision.remaining(EditKind.Natural).single()
        assertEquals("Voy a tomar una ducha", natural.from)
        assertEquals("More usual", natural.why)

        val reworded = revision.accept(natural.id)
        assertEquals("Me voy a duchar y te llamo despues", reworded.workingText)
        // The accent fix is elsewhere, so it's still on offer.
        assertEquals(listOf("después"), reworded.replacements(EditKind.Fix))
        assertEquals("Me voy a duchar y te llamo después", reworded.preview(EditKind.Fix).text)
    }

    @Test
    fun theRewordingWinsWhereItTouchesAFix() {
        val original = "Bon dia! Com estas amb la pluja?"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                moreNatural = true,
                natural = "Bon dia! Com portes la pluja?",
                naturalChanges = listOf(VerdictChange("estàs amb", "portes", why = "Usual way to say it")),
            ),
        ).revision
        val fix = revision.remaining(EditKind.Fix).single()
        val natural = revision.remaining(EditKind.Natural).single()

        // Rewording first: the fix inside it is retired.
        val reworded = revision.accept(natural.id)
        assertEquals("Bon dia! Com portes la pluja?", reworded.workingText)
        assertTrue(reworded.remaining(EditKind.Fix).isEmpty())
        assertEquals(reworded, reworded.accept(fix.id))

        // Fix first: the rewording stays on offer, and accepting it replaces the fix.
        val fixed = revision.accept(fix.id)
        assertEquals("Bon dia! Com estàs amb la pluja?", fixed.workingText)
        assertEquals(listOf(natural), fixed.remaining(EditKind.Natural))
        assertEquals("Bon dia! Com portes la pluja?", fixed.preview(EditKind.Natural).text)
        val both = fixed.accept(natural.id)
        assertEquals("Bon dia! Com portes la pluja?", both.workingText)
        assertTrue(both.remaining(EditKind.Fix).isEmpty())

        // Undo brings back what was accepted and what it retired.
        assertEquals("Bon dia! Com estàs amb la pluja?", both.undo().workingText)
        assertEquals(revision.remaining(EditKind.Fix), reworded.undo().remaining(EditKind.Fix))
        assertEquals(original, fixed.undo().workingText)
    }

    @Test
    fun aRewordingIncludesPunctuationInsertedAtItsEdge() {
        val revision = reviewed(
            "estoy en casa y luego salgo",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Estoy en casa, y luego salgo.",
                moreNatural = true,
                natural = "Estoy en casa. Luego salgo.",
            ),
        ).revision
        val natural = revision.remaining(EditKind.Natural).single()

        // The comma is inserted where the rewording starts: it shares no original
        // characters with it, but the rewording already replaces it.
        val reworded = revision.accept(natural.id)
        assertEquals(listOf("Estoy", "."), reworded.replacements(EditKind.Fix))
        assertEquals("Estoy en casa. Luego salgo.", reworded.acceptAll(EditKind.Fix).workingText)

        // All fixes first, then the rewording: the comma doesn't survive.
        assertEquals(
            "Estoy en casa. Luego salgo.",
            revision.acceptAll(EditKind.Fix).accept(natural.id).workingText,
        )
    }

    @Test
    fun changesThatOnlySitNextToEachOtherDontRetireEachOther() {
        val revision = reviewed(
            "vamos a comer algunos snacks de de una bodega o algo",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "vamos a comer algunos snacks de una bodega o algo.",
                moreNatural = true,
                natural = "vamos a comer unos snacks de una tienda o algo.",
            ),
        ).revision

        // The rewordings don't touch the fixes, so accepting all of one kind leaves the other.
        val fixed = revision.acceptAll(EditKind.Fix)
        assertEquals(listOf("unos", "tienda"), fixed.remaining(EditKind.Natural).map { it.replacement })
        assertEquals(
            "vamos a comer unos snacks de una tienda o algo.",
            fixed.acceptAll(EditKind.Natural).workingText,
        )
    }

    @Test
    fun rewordingsDontRepeatTheFixes() {
        val original = "Hola bebé estoy en casa y neo está en el suelo y tengo algunas algunas cosas que voy a hacer"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo algunas cosas que voy a hacer",
                moreNatural = true,
                natural = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo unas cosas que hacer",
            ),
        ).revision

        assertEquals(listOf(",", ".", "Estoy", "Neo", "algunas"), revision.replacements(EditKind.Fix))
        // Only the rewordings, mapped onto the original; the punctuation fixes aren't repeated.
        val naturals = revision.remaining(EditKind.Natural)
        assertEquals(listOf("algunas algunas" to "unas", "voy a hacer" to "hacer"), naturals.map { it.from to it.replacement })

        // Accepting the rewordings alone leaves the original's other errors for the fixes.
        val reworded = revision.acceptAll(EditKind.Natural)
        assertEquals("Hola bebé estoy en casa y neo está en el suelo y tengo unas cosas que hacer", reworded.workingText)
        assertEquals(listOf(",", ".", "Estoy", "Neo"), reworded.replacements(EditKind.Fix))
    }

    @Test
    fun aRewordingThatUndoesAFixIsDropped() {
        val revision = reviewed(
            "te llamo despues",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "te llamo después",
                moreNatural = true,
                // Written on the original despite the instructions, so it only undoes the fix.
                natural = "te llamo despues",
            ),
        ).revision

        assertTrue(revision.edits(EditKind.Natural).isEmpty())
        assertEquals(listOf("después"), revision.replacements(EditKind.Fix))
    }

    @Test
    fun deletedConjunctionJoinsTheWordNextToIt() {
        val revision = reviewed(
            "en el sofá y neo está tranquilo",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "en el sofá. Neo está tranquilo"),
        ).revision

        assertEquals(listOf("" to ".", "y neo" to "Neo"), revision.remaining(EditKind.Fix).map { it.from to it.replacement })
    }

    @Test
    fun strictSentenceBreaksAreAFullStopAndTheNewSentence() {
        val revision = reviewed(
            "estoy en el sofá y neo está en el suelo y tengo cosas que hacer",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Estoy en el sofá, y Neo está en el suelo. Tengo cosas que hacer.",
            ),
        ).revision

        assertEquals(
            listOf("estoy" to "Estoy", "" to ",", "neo" to "Neo", "" to ".", "y tengo" to "Tengo", "" to "."),
            revision.remaining(EditKind.Fix).map { it.from to it.replacement },
        )
    }

    @Test
    fun acceptedChangesSurviveARecheckWhereTheyDidntChange() {
        val original = "Hola bebé, tomamos las cervezas que me ha traído de Montreal"
        val first = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Hola, bebé, tomamos las cervezas que me ha traído de Montreal"),
        ).revision
        val accepted = first.acceptAll(EditKind.Fix).acceptedEdits

        // After settling "who brought the beers", the re-check adds a fix and keeps the comma.
        val second = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Hola, bebé, tomamos las cervezas que he traído de Montreal"),
        ).revision.acceptMatching(accepted)

        assertEquals("Hola, bebé, tomamos las cervezas que me ha traído de Montreal", second.workingText)
        assertEquals(listOf("me ha" to "he"), second.remaining(EditKind.Fix).map { it.from to it.replacement })
    }

    @Test
    fun aRecheckKeepsEachAcceptedChangeOnce() {
        val verdict = Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "espera...")
        val first = reviewed("espera", verdict).revision
        assertEquals(listOf(".", ".", "."), first.replacements(EditKind.Fix))
        val accepted = first.accept(first.edits.first().id).acceptedEdits

        val second = reviewed("espera", verdict).revision.acceptMatching(accepted)

        assertEquals("espera.", second.workingText)
        assertEquals(2, second.remaining(EditKind.Fix).size)
    }

    @Test
    fun aReasonGoesToTheChangeItDescribesExactly() {
        val revision = reviewed(
            "asi que si",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "así que sí",
                fixes = listOf(VerdictChange("si", "sí", why = "Affirmative sí takes an accent")),
            ),
        ).revision

        assertEquals(
            listOf("asi" to null, "si" to "Affirmative sí takes an accent"),
            revision.remaining(EditKind.Fix).map { it.from to it.why },
        )
    }

    @Test
    fun replaceAllIsOneUndoStep() {
        val original = "hola q tal bb"
        val revision = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "hola, q tal, bb?"),
        ).revision

        val all = revision.acceptAll(EditKind.Fix)
        assertEquals("hola, q tal, bb?", all.workingText)
        assertEquals(original, all.undo().workingText)
        assertFalse(all.undo().canUndo)
    }

    @Test
    fun deletedWordsAreShownWithTheirNeighbour() {
        val revision = reviewed(
            "Ayer yo fui a la playa",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ayer fui a la playa"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("yo fui", fix.from)
        assertEquals("fui", fix.replacement)
    }

    @Test
    fun insertedWordsDontCarryTheSpaceIntoTheHighlight() {
        val revision = reviewed(
            "Ahir vaig mercat",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ahir vaig al mercat"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        val preview = revision.preview(EditKind.Fix)
        assertEquals("Ahir vaig al mercat", preview.text)
        assertEquals("al ", preview.text.substring(preview.ranges.getValue(fix.id)))
    }

    @Test
    fun onlyRequestedJudgmentsBecomeEdits() {
        val original = "Com estas amb la pluja?"
        val verdict = Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Com estàs amb la pluja?",
            moreNatural = true,
            natural = "Com portes la pluja?",
        )
        val fixOnly = interpret(original, verdict, Judgments.FixOnly) as CheckResult.Reviewed
        assertEquals(listOf(EditKind.Fix), fixOnly.kinds)
        assertTrue(fixOnly.revision.edits(EditKind.Natural).isEmpty())

        val naturalOnly = interpret(original, verdict, Judgments.NaturalizeOnly) as CheckResult.Reviewed
        assertEquals(listOf(EditKind.Natural), naturalOnly.kinds)
        assertTrue(naturalOnly.revision.edits(EditKind.Fix).isEmpty())

        val nothingToFix = interpret(original, verdict.copy(hasErrors = false), Judgments.FixOnly) as CheckResult.Reviewed
        assertTrue(nothingToFix.looksGood)
    }

    @Test
    fun aCorrectionIdenticalToTheOriginalLooksGood() {
        val original = "Ens veiem demà a les set?"
        val verdict = Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = " $original ")
        assertTrue((interpret(original, verdict) as CheckResult.Reviewed).looksGood)
    }

    @Test
    fun resolvedOnceEverySuggestionIsAcceptedOrOvertaken() {
        val result = interpret(
            "Bon dia! Com estas amb la pluja?",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Bon dia! Com estàs amb la pluja?",
                moreNatural = true,
                natural = "Bon dia! Com portes la pluja?",
            ),
        ) as CheckResult.Reviewed
        assertFalse(result.isResolved)
        val reworded = result.copy(revision = result.revision.acceptAll(EditKind.Natural))
        assertTrue(reworded.isResolved)
    }

    @Test
    fun unclearAndWrongLanguage() {
        assertEquals(CheckResult.Unclear, interpret("x", Verdict(status = Verdict.Status.Unclear)))
        assertEquals(
            CheckResult.WrongLanguage("Catalan"),
            interpret("x", Verdict(status = Verdict.Status.WrongLanguage), expectedLanguage = "Catalan"),
        )
        // What the model found travels with it; blank means unknown.
        assertEquals(
            CheckResult.WrongLanguage("Catalan", found = "Spanish"),
            interpret(
                "x",
                Verdict(status = Verdict.Status.WrongLanguage, language = "Spanish"),
                expectedLanguage = "Catalan",
            ),
        )
        // Without a chosen language there's nothing to be wrong about.
        assertEquals(CheckResult.Unclear, interpret("x", Verdict(status = Verdict.Status.WrongLanguage)))
    }

    @Test
    fun aMeaningThatJustRepeatsTheTextIsDropped() {
        val original = "Charles y yo vamos a tomar las cervezas que me ha traído de Montreal"
        fun meaningOf(meaning: String) =
            (interpret(original, Verdict(status = Verdict.Status.Ok, meaning = meaning)) as CheckResult.Reviewed).meaning

        assertEquals("", meaningOf("Charles y yo vamos a tomar las cervezas que me ha traído de Montreal."))
        assertEquals(
            "Charles and I are going to drink the beers he brought me from Montreal.",
            meaningOf("Charles and I are going to drink the beers he brought me from Montreal."),
        )
    }
}
