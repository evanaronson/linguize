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
    fun independentRepairsStaySeparateFromAnExplicitSentenceBoundaryFix() {
        val original = "hola q tal bb estás bien te encanta esta musica no"
        val corrected = "hola, q tal, bb? Estás bien? Te encanta esta música, no?"
        val revision = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected,
            fixes = listOf(
                VerdictChange("", ","), VerdictChange("", ","), VerdictChange(" estás", "? Estás"),
                VerdictChange(" te", "? Te"),
                VerdictChange("musica", "música"), VerdictChange("", ","), VerdictChange("", "?"),
            ))).revision

        assertEquals(
            listOf(",", ",", "? Estás", "? Te", "música", ",", "?"),
            revision.replacements(EditKind.Fix),
        )
        assertEquals(corrected, revision.preview(EditKind.Fix).text)
        val boundary = revision.edits(EditKind.Fix).single { it.replacement == "? Te" }
        assertEquals("hola q tal bb estás bien? Te encanta esta musica no", revision.accept(boundary.id).workingText)
        assertEquals(original, revision.accept(boundary.id).undo().workingText)
    }

    @Test
    fun anAccentRepairCannotHideUnreportedSentenceInitialCase() {
        val original = "hola q tal bb estas bien te encanta esta musica no"
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "hola, q tal, bb? Estás bien? Te encanta esta música, no?",
            fixes = listOf(
                VerdictChange("", ","), VerdictChange("", ","), VerdictChange("", "?"),
                VerdictChange("estas", "Estás"), VerdictChange(" te", "? Te"),
                VerdictChange("musica", "música"), VerdictChange("", ","), VerdictChange("", "?"),
            )))
        assertEquals(listOf(",", ",", "? Te", "música", ",", "?"), result.revision.replacements(EditKind.Fix))
        for (edit in result.revision.edits(EditKind.Fix)) {
            assertTrue(result.revision.accept(edit.id).workingText.contains("bb estas"))
        }
        val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
        assertEquals("hola, q tal, bb estas bien? Te encanta esta música, no?", accepted.revision.workingText)
        assertEquals(original, accepted.revision.undo().workingText)
        assertTrue(EditKind.Fix in result.withheld)
        assertFalse(accepted.isResolved)
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
                natural = "Voy a ducharme y te llamo después",
                naturalChanges = listOf(VerdictChange("tomar una ducha", "ducharme", why = "More usual")),
            ),
        ).revision

        val natural = revision.remaining(EditKind.Natural).single()
        assertEquals("tomar una ducha", natural.from)
        assertEquals("More usual", natural.why)

        val reworded = revision.accept(natural.id)
        assertEquals("Voy a ducharme y te llamo despues", reworded.workingText)
        // The accent fix is elsewhere, so it's still on offer.
        assertEquals(listOf("después"), reworded.replacements(EditKind.Fix))
        assertEquals("Voy a ducharme y te llamo después", reworded.preview(EditKind.Fix).text)
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
    fun sentencePunctuationAndCapitalisationStayWithCorrectness() {
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
        assertTrue(revision.edits(EditKind.Natural).isEmpty())
        assertEquals("Estoy en casa, y luego salgo.", revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun changesThatOnlySitNextToEachOtherDontRetireEachOther() {
        val original = "vamos a comer algunos snaks de una bodega o algo"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "vamos a comer algunos snacks de una bodega o algo.",
                moreNatural = true,
                natural = "vamos a comer unos snacks de una tienda o algo.",
                naturalChanges = listOf(VerdictChange("algunos", "unos"), VerdictChange("bodega", "tienda")),
            ),
        ).revision

        // The rewordings don't touch the fixes, so accepting all of one kind leaves the other.
        val fixed = revision.acceptAll(EditKind.Fix)
        assertEquals(listOf("unos", "tienda"), fixed.remaining(EditKind.Natural).map { it.replacement })
        assertEquals(
            "vamos a comer unos snacks de una tienda o algo.",
            fixed.acceptAll(EditKind.Natural).workingText,
        )
        val reworded = revision.acceptAll(EditKind.Natural)
        assertEquals("vamos a comer unos snaks de una tienda o algo", reworded.workingText)
        assertEquals(listOf("snacks", "."), reworded.replacements(EditKind.Fix))
        assertEquals(original, reworded.undo().workingText)
    }

    @Test
    fun rewordingsCannotUseAnUnsupportedDuplicateDeletionAsTheirBase() {
        val original = "vamos a comer algunos snacks de de una bodega o algo"
        val result = reviewed(original, Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "vamos a comer algunos snacks de una bodega o algo.",
            moreNatural = true,
            natural = "vamos a comer unos snacks de una tienda o algo.",
            naturalChanges = listOf(VerdictChange("algunos", "unos"), VerdictChange("bodega", "tienda")),
        ))
        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
        assertEquals("$original.", accepted.revision.workingText)
        assertFalse(accepted.isResolved)
        assertFalse(accepted.looksGood)
    }

    @Test
    fun rewordingsDontRepeatTheFixes() {
        val original = "Hola bebé estoy en casa y neo está en el suelo y tengo algunaz cosas que voy a hacer"
        val revision = reviewed(
            original,
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo algunas cosas que voy a hacer",
                fixes = listOf(VerdictChange("", ","), VerdictChange(" estoy", ". Estoy"),
                    VerdictChange("neo", "Neo"), VerdictChange("algunaz", "algunas")),
                moreNatural = true,
                natural = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo unas cosas que hacer",
                naturalChanges = listOf(VerdictChange("algunas", "unas"), VerdictChange("voy a hacer", "hacer")),
            ),
        ).revision

        assertEquals(listOf(",", ". Estoy", "Neo", "algunas"), revision.replacements(EditKind.Fix))
        // Only the rewordings, mapped onto the original; the punctuation fixes aren't repeated.
        val naturals = revision.remaining(EditKind.Natural)
        assertEquals(listOf("algunaz" to "unas", "voy a" to ""),
            naturals.map { it.from.trim() to it.replacement.trim() })
        assertEquals("Hola bebé estoy en casa y neo está en el suelo y tengo unas cosas que voy a hacer",
            revision.accept(naturals[0].id).workingText)
        assertEquals("Hola bebé estoy en casa y neo está en el suelo y tengo algunaz cosas que hacer",
            revision.accept(naturals[1].id).workingText)

        // Accepting the rewordings alone leaves the original's other errors for the fixes.
        val reworded = revision.acceptAll(EditKind.Natural)
        assertEquals("Hola bebé estoy en casa y neo está en el suelo y tengo unas cosas que hacer", reworded.workingText)
        assertEquals(listOf(",", ". Estoy", "Neo"), reworded.replacements(EditKind.Fix))
    }

    @Test
    fun aReportedDuplicateDeletionStillCannotSupportDependentRewordings() {
        val original = "Hola bebé estoy en casa y neo está en el suelo y tengo algunas algunas cosas que voy a hacer"
        val result = reviewed(original, Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo algunas cosas que voy a hacer",
            fixes = listOf(VerdictChange("", ","), VerdictChange(" estoy", ". Estoy"),
                VerdictChange("neo", "Neo"), VerdictChange("algunas algunas", "algunas")),
            moreNatural = true,
            natural = "Hola, bebé. Estoy en casa y Neo está en el suelo y tengo unas cosas que hacer",
            naturalChanges = listOf(VerdictChange("algunas", "unas"), VerdictChange("voy a hacer", "hacer")),
        ))
        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertEquals(listOf(",", ". Estoy", "Neo"), result.revision.replacements(EditKind.Fix))
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
        assertEquals("Hola, bebé. Estoy en casa y Neo está en el suelo y tengo algunas algunas cosas que voy a hacer",
            accepted.revision.workingText)
        assertFalse(accepted.isResolved)
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
    fun aWithheldConjunctionRewriteCannotOfferItsCaseFragment() {
        val result = reviewed(
            "en el sofá y neo está tranquilo",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "en el sofá. Neo está tranquilo"),
        )
        val revision = result.revision
        assertEquals(setOf(EditKind.Fix), result.withheld)
        assertTrue(revision.remaining(EditKind.Fix).isEmpty())
        val accepted = result.copy(revision = revision.acceptAll(EditKind.Fix))
        assertEquals("en el sofá y neo está tranquilo", accepted.revision.workingText)
        assertFalse(accepted.isResolved)
        assertFalse(accepted.looksGood)
        assertEquals("en el sofá y neo está tranquilo", accepted.revision.undo().workingText)
    }

    @Test
    fun aConjunctionAndSentenceBoundaryCannotBeAcceptedAsSeparateFragments() {
        val original = "en el sofá y Neo está tranquilo"
        val corrected = "en el sofá. Neo está tranquilo"
        for ((source, target) in listOf(original to corrected, corrected to original)) {
            val result = reviewed(source,
                Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = target))
            assertEquals(setOf(EditKind.Fix), result.withheld)
            assertTrue(result.revision.remaining(EditKind.Fix).isEmpty())
            val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
            assertEquals(source, accepted.revision.workingText)
            assertFalse(accepted.isResolved)
            assertFalse(accepted.looksGood)
        }
    }

    @Test
    fun aSimpleConjunctionDeletionBetweenAnchorsCanBeAcceptedAndUndone() {
        val original = "en el sofá y Neo está tranquilo"
        val corrected = "en el sofá Neo está tranquilo"
        val result = reviewed(original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected))
        assertTrue(result.withheld.isEmpty())
        val fix = result.revision.remaining(EditKind.Fix).single()
        assertEquals(" y" to "", fix.from to fix.replacement)
        val accepted = result.revision.accept(fix.id)
        assertEquals(corrected, accepted.workingText)
        assertTrue(accepted.remaining(EditKind.Fix).isEmpty())
        assertEquals(original, accepted.undo().workingText)
    }

    @Test
    fun aReportedSentenceBoundaryKeepsUnrelatedPunctuationAndCapitalisationSeparate() {
        val revision = reviewed(
            "estoy en el sofá y neo está en el suelo tengo cosas que hacer",
            Verdict(
                status = Verdict.Status.Ok,
                hasErrors = true,
                corrected = "Estoy en el sofá, y Neo está en el suelo. Tengo cosas que hacer.",
                fixes = listOf(VerdictChange("estoy", "Estoy"), VerdictChange("", ","),
                    VerdictChange("neo", "Neo"), VerdictChange(" tengo", ". Tengo"), VerdictChange("", ".")),
            ),
        ).revision

        assertEquals(
            listOf("estoy" to "Estoy", "" to ",", "neo" to "Neo", " tengo" to ". Tengo", "" to "."),
            revision.remaining(EditKind.Fix).map { it.from to it.replacement },
        )
        assertEquals("Estoy en el sofá, y Neo está en el suelo. Tengo cosas que hacer.",
            revision.acceptAll(EditKind.Fix).workingText)
        val boundary = revision.remaining(EditKind.Fix).single { it.replacement == ". Tengo" }
        assertEquals("estoy en el sofá y neo está en el suelo. Tengo cosas que hacer",
            revision.accept(boundary.id).workingText)
    }

    @Test
    fun anUnsupportedSentenceRewriteRemainsIncompleteAfterAvailableFixes() {
        val original = "estoy en el sofá y neo está en el suelo y tengo cosas que hacer"
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Estoy en el sofá, y Neo está en el suelo. Tengo cosas que hacer."))
        assertEquals(setOf(EditKind.Fix), result.withheld)
        assertTrue(result.revision.remaining(EditKind.Fix).none { it.from == " y" })
        assertTrue(result.revision.remaining(EditKind.Fix).none { it.from == "tengo" || it.replacement == "Tengo" })
        val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
        assertFalse(accepted.isResolved)
        assertFalse(accepted.looksGood)
        assertEquals(original, accepted.revision.undo().workingText)
    }

    @Test
    fun acceptedChangesSurviveARecheckWhereTheyDidntChange() {
        val original = "Hola bebé, tomamos las cervezaz que me ha traído de Montreal"
        val first = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Hola, bebé, tomamos las cervezaz que me ha traído de Montreal"),
        ).revision
        val accepted = first.acceptAll(EditKind.Fix).acceptedEdits

        // The re-check adds a spelling fix and keeps the already accepted comma.
        val second = reviewed(
            original,
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Hola, bebé, tomamos las cervezas que me ha traído de Montreal"),
        ).revision.acceptMatching(accepted)

        assertEquals("Hola, bebé, tomamos las cervezaz que me ha traído de Montreal", second.workingText)
        assertEquals(1, second.acceptedCount)
        assertEquals("Hola, bebé, tomamos las cervezas que me ha traído de Montreal", second.preview(EditKind.Fix).text)
    }

    @Test
    fun aRecheckKeepsAcceptedChangesWhenANewDependentRewriteIsWithheld() {
        val original = "Hola bebé, tomamos las cervezas que me ha traído de Montreal"
        val first = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Hola, bebé, tomamos las cervezas que me ha traído de Montreal")).revision
        val accepted = first.acceptAll(EditKind.Fix).acceptedEdits
        val checked = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Hola, bebé, tomamos las cervezas que he traído de Montreal"))
        val second = checked.copy(revision = checked.revision.acceptMatching(accepted))
        assertEquals(setOf(EditKind.Fix), second.withheld)
        assertEquals("Hola, bebé, tomamos las cervezas que me ha traído de Montreal", second.revision.workingText)
        assertEquals(1, second.revision.acceptedCount)
        assertTrue(second.revision.remaining(EditKind.Fix).isEmpty())
        assertFalse(second.isResolved)
        assertFalse(second.looksGood)
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
    fun deletedWordsKeepTheirOwnSpan() {
        val revision = reviewed(
            "Ayer yo fui a la playa",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ayer fui a la playa"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        assertEquals("yo", fix.from.trim())
        assertEquals("", fix.replacement)
        assertEquals("Ayer fui a la playa", revision.accept(fix.id).workingText)
        assertEquals("Ayer yo fui a la playa", revision.accept(fix.id).undo().workingText)
    }

    @Test
    fun insertedWordsKeepTheirSeparatingSpaceInTheEdit() {
        val revision = reviewed(
            "Ahir vaig mercat",
            Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Ahir vaig al mercat"),
        ).revision

        val fix = revision.remaining(EditKind.Fix).single()
        val preview = revision.preview(EditKind.Fix)
        assertEquals("Ahir vaig al mercat", preview.text)
        assertEquals(" al", preview.text.substring(preview.ranges.getValue(fix.id)))
        assertEquals("Ahir vaig al mercat", revision.accept(fix.id).workingText)
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
            naturalChanges = listOf(VerdictChange("estàs amb", "portes")),
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
    fun discardedRewordingsCannotMakeTheTextLookGood() {
        val original = "Quan parlo català tinc que usar l'aplicació."
        val result = reviewed(original, Verdict(
            status = Verdict.Status.Ok,
            moreNatural = true,
            natural = "Quan parlo català he de fer servir l'aplicació.",
            naturalChanges = listOf(VerdictChange("tinc que usar", "he de fer servir", "More natural obligation and verb")),
        ))

        assertTrue(result.revision.edits.isEmpty())
        assertEquals(setOf(EditKind.Natural), result.withheld)
        assertFalse(result.looksGood)
        assertFalse(result.isResolved)
    }

    @Test
    fun acceptingAvailableFixesDoesNotEraseAWithheldRewording() {
        val result = reviewed("Bon dia. Com estas?", Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Bon dia. Com estàs?",
            moreNatural = true,
            natural = "Hola, com et trobes?",
            naturalChanges = listOf(VerdictChange("Bon dia. Com estàs?", "Hola, com et trobes?", "More natural greeting")),
        ))
        assertEquals(setOf(EditKind.Natural), result.withheld)
        assertEquals(1, result.revision.edits.size)
        val accepted = result.copy(revision = result.revision.acceptAll(EditKind.Fix))
        assertEquals("Bon dia. Com estàs?", accepted.revision.workingText)
        assertEquals(setOf(EditKind.Natural), accepted.withheld)
        assertFalse(accepted.looksGood)
        assertFalse(accepted.isResolved)
        assertEquals("Bon dia. Com estas?", accepted.revision.undo().workingText)
    }

    @Test
    fun oneRejectedRewordingDoesNotHideAnotherUsableOne() {
        val original = "És bonic. Quan parlo català tinc que usar l'aplicació."
        val result = reviewed(original, Verdict(
            status = Verdict.Status.Ok,
            moreNatural = true,
            natural = "És preciós. Quan parlo català he de fer servir l'aplicació.",
            naturalChanges = listOf(
                VerdictChange("bonic", "preciós", "More expressive"),
                VerdictChange("tinc que usar", "he de fer servir", "More natural obligation and verb"),
            ),
        ))
        assertEquals(setOf(EditKind.Natural), result.withheld)
        val offered = result.revision.edits.single()
        assertEquals("bonic", offered.from)
        val accepted = result.copy(revision = result.revision.accept(offered.id))
        assertEquals("És preciós. Quan parlo català tinc que usar l'aplicació.", accepted.revision.workingText)
        assertFalse(accepted.isResolved)
        assertEquals(setOf(EditKind.Natural), accepted.withheld)
    }

    @Test
    fun aPositiveJudgmentWithNoUsableAnswerIsNotSuccess() {
        val verdict = Verdict(status = Verdict.Status.Ok, hasErrors = true, moreNatural = true)
        val result = reviewed("Bon dia", verdict)
        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertFalse(result.looksGood)

        val fixOnly = interpret("Bon dia", verdict, Judgments.FixOnly) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Fix), fixOnly.withheld)
        val naturalOnly = interpret("Bon dia", verdict, Judgments.NaturalizeOnly) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Natural), naturalOnly.withheld)
    }

    @Test
    fun rejectingDependentGrammarKeepsAnIndependentSpellingFixUsable() {
        val original = "Quan parlo catala tinc que usar aquesta aplicació."
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Quan parlo català he d'usar aquesta aplicació."))
        val fix = result.revision.edits(EditKind.Fix).single()
        assertEquals("catala", fix.from)
        assertEquals("català", fix.replacement)
        assertEquals(setOf(EditKind.Fix), result.withheld)

        val accepted = result.copy(revision = result.revision.accept(fix.id))
        assertEquals("Quan parlo català tinc que usar aquesta aplicació.", accepted.revision.workingText)
        assertTrue(accepted.revision.remaining(EditKind.Fix).isEmpty())
        assertFalse(accepted.looksGood)
        assertFalse(accepted.isResolved)
        assertEquals(original, accepted.revision.undo().workingText)
    }

    @Test
    fun noChangeAndUnrequestedJudgmentsDoNotBecomeWithheld() {
        val original = "Bon dia"
        val unchanged = reviewed(original, Verdict(status = Verdict.Status.Ok,
            moreNatural = true, natural = original))
        assertTrue(unchanged.withheld.isEmpty())
        assertTrue(unchanged.looksGood)

        val unrequested = interpret(original, Verdict(status = Verdict.Status.Ok, moreNatural = true),
            Judgments.FixOnly) as CheckResult.Reviewed
        assertTrue(unrequested.withheld.isEmpty())
        assertTrue(unrequested.looksGood)
    }

    @Test
    fun aRejectedNewLinkCannotLookLikeNoFixWasNeeded() {
        val result = reviewed("Hola", Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Hola https://example.com"))
        assertTrue(EditKind.Fix in result.withheld)
        assertFalse(result.looksGood)
        assertFalse(result.revision.preview(EditKind.Fix).text.contains("example.com"))
    }

    @Test
    fun rewordingsCannotDependOnADiscardedCorrection() {
        val result = reviewed("Hola bonic. Tinc un erro.", Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Hola example.com bonic. Tinc un error.",
            moreNatural = true,
            natural = "Hola example.com preciós. Tinc un error.",
            naturalChanges = listOf(VerdictChange("bonic", "preciós")),
        ))

        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        assertEquals("Hola bonic. Tinc un error.", result.revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun rewordingsOnRetainedCorrectionsKeepDistinctAcceptActions() {
        val original = "Hola bonic. Tinc un erro."
        val result = reviewed(original, Verdict(
            status = Verdict.Status.Ok,
            hasErrors = true,
            corrected = "Hola example.com bonic. Tinc un error.",
            moreNatural = true,
            natural = "Hola preciós. Tinc un error.",
            naturalChanges = listOf(VerdictChange("bonic", "preciós")),
        ))
        val revision = result.revision
        assertEquals(setOf(EditKind.Fix), result.withheld)
        assertEquals(revision.edits.size, revision.edits.map { it.id }.toSet().size)
        val fix = revision.edits(EditKind.Fix).single()
        val natural = revision.edits(EditKind.Natural).single()
        assertEquals("Hola bonic. Tinc un error.", revision.accept(fix.id).workingText)
        assertEquals("Hola preciós. Tinc un erro.", revision.accept(natural.id).workingText)
        assertEquals("Hola preciós. Tinc un error.", revision.accept(natural.id).accept(fix.id).workingText)
        assertEquals(original, revision.accept(natural.id).undo().workingText)
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
                naturalChanges = listOf(VerdictChange("estàs amb", "portes")),
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

    @Test
    fun detectedLanguageMismatchCannotLookGoodEvenWhenTheModelSaysOk() {
        for (judgments in Judgments.entries) {
            assertEquals(CheckResult.WrongLanguage("Catalan", "English"),
                interpret("Hello, how are you?", Verdict(status = Verdict.Status.Ok, language = "English"),
                    judgments, expectedLanguage = "Catalan"))
        }
        assertEquals(CheckResult.WrongLanguage("Spanish", "Catalan"),
            interpret("Bon dia", Verdict(status = Verdict.Status.Ok, language = "Catalan"),
                expectedLanguage = "Spanish"))
    }

    @Test
    fun matchingLanguageAndAutoDetectionRemainSupported() {
        assertTrue(interpret("Bon dia", Verdict(status = Verdict.Status.Ok, language = " catalan "),
            expectedLanguage = "Catalan") is CheckResult.Reviewed)
        assertTrue(interpret("Bon dia", Verdict(status = Verdict.Status.Ok, language = "Catalan (Central)"),
            expectedLanguage = "Catalan") is CheckResult.Reviewed)
        assertTrue(interpret("Hello", Verdict(status = Verdict.Status.Ok, language = "English"))
            is CheckResult.Reviewed)
    }
}
