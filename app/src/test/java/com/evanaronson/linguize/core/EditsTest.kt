package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases in turning the model's texts into edits, each one a bug that was found and fixed. */
class EditsTest {
    private fun revision(original: String, corrected: String = "", natural: String = "",
        naturalChanges: List<VerdictChange> = if (natural.isEmpty()) emptyList()
            else listOf(VerdictChange(corrected.ifEmpty { original }, natural)),
        fixChanges: List<VerdictChange> = emptyList()) =
        (
            interpret(
                original,
                Verdict(
                    status = Verdict.Status.Ok,
                    hasErrors = corrected.isNotEmpty(),
                    corrected = corrected,
                    moreNatural = natural.isNotEmpty(),
                    natural = natural,
                    naturalChanges = naturalChanges,
                    fixes = fixChanges,
                ),
            ) as CheckResult.Reviewed
            ).revision

    private fun Revision.changes(kind: EditKind) = remaining(kind).map { it.from to it.replacement }

    @Test
    fun aWordAndItsPunctuationCannotBecomeIndependentAcceptActions() {
        for ((original, corrected) in listOf(
            "hola cómo estás" to "hola amor, cómo estás",
            "foo and Bar" to "foo. Bar",
            "foo. Bar" to "foo and Bar",
            "foo and Bar" to "foo; Bar",
            "foo; Bar" to "foo and Bar",
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = corrected)) as CheckResult.Reviewed
            assertTrue(result.revision.edits.isEmpty())
            assertTrue(EditKind.Fix in result.withheld)
            assertEquals(original, result.revision.acceptAll(EditKind.Fix).workingText)
            assertFalse(result.isResolved)
        }
    }

    @Test
    fun anIndependentWordInsertionKeepsItsSeparatingSpace() {
        val r = revision("hola cómo estás", corrected = "hola amor cómo estás")
        assertEquals("hola amor cómo estás", r.accept(r.edits.single().id).workingText)
        assertEquals("hola cómo estás", r.acceptAll(EditKind.Fix).undo().workingText)
    }

    @Test
    fun hyphensAndMiddleDotsBelongToTheWord() {
        assertEquals(listOf("dirho" to "dir-ho"), revision("vull dirho ara", corrected = "vull dir-ho ara").changes(EditKind.Fix))
        assertEquals(listOf("colegi" to "col·legi"), revision("al colegi", corrected = "al col·legi").changes(EditKind.Fix))
    }

    @Test
    fun anEmojiIsOneCharacter() {
        assertEquals(listOf("😀" to "😂"), revision("hola 😀", corrected = "hola 😂").changes(EditKind.Fix))
    }

    @Test
    fun aRewordingInsertedBeforeAnInsertedFixGoesInFrontOfIt() {
        val revision = revision("que tal", corrected = "¿Qué tal?", natural = "Hola ¿Qué tal?")
        val expected = "Hola ¿Qué tal?"
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aRewordingInsertedAfterAnInsertedFixKeepsTheFix() {
        val revision = revision("gracias por todo", corrected = "Gracias por todo.", natural = "Gracias por todo de verdad.")
        val expected = "Gracias por todo de verdad."
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun changesToSpacingAloneAreSuggestedAndApplied() {
        assertEquals("hola que tal?", revision("hola  que tal", corrected = "hola que tal?").acceptAll(EditKind.Fix).workingText)
        val paragraph = revision("hola\n\nque tal", corrected = "hola\nque tal")
        assertTrue(paragraph.edits(EditKind.Fix).isNotEmpty())
        assertEquals("hola\nque tal", paragraph.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun aDeletedWordDoesNotBorrowAnUnchangedNeighbourForVisibility() {
        val r = revision("hola de amigos", corrected = "hola amigos")
        val fix = r.remaining(EditKind.Fix).single()
        assertEquals(" de", fix.from)
        assertEquals("", fix.replacement)
        assertEquals("hola amigos", r.accept(fix.id).workingText)
        assertTrue(r.preview(EditKind.Fix).ranges.containsKey(fix.id))
    }

    @Test
    fun aReportedChangeThatChangesNothingIsIgnored() {
        val revision = revision(
            "Voy a tomar una ducha y te llamo despues",
            corrected = "Voy a tomar una ducha y te llamo después",
            natural = "Voy a ducharme y te llamo después",
            naturalChanges = listOf(VerdictChange("tomar una ducha", "ducharme"), VerdictChange("después", "después")),
        )
        assertEquals(listOf("tomar una ducha" to "ducharme"), revision.changes(EditKind.Natural))
    }

    @Test
    fun aFixReplacedByARewordingIsntCountedAsApplied() {
        val revision = revision("Com estas amb la pluja?", corrected = "Com estàs amb la pluja?", natural = "Com portes la pluja?")
        val both = revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural)
        assertEquals("Com portes la pluja?", both.workingText)
        assertEquals(1, both.acceptedCount)
    }

    /** Accepting everything gives [expected] in either order of kinds, and one edit at a time in any order. */
    private fun assertAcceptingAllGives(expected: String, revision: Revision) {
        assertEquals(expected, revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix).workingText)
        assertEquals(expected, revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
        val ids = revision.edits.map { it.id }
        for (order in listOf(ids, ids.reversed())) {
            assertEquals(expected, order.fold(revision) { r, id -> r.accept(id) }.workingText)
        }
    }

    @Test
    fun insertionsAtOnePlaceKeepTheNaturalTextsOrderWhateverTheirKind() {
        // A rewording inserted between two inserted fixes, past a space.
        assertAcceptingAllGives(
            "dijo bajito: «hola»",
            revision("dijo hola", corrected = "dijo: «hola»", natural = "dijo bajito: «hola»"),
        )
        // A rewording inserted before several inserted fixes.
        assertAcceptingAllGives("Oye ¡¿Vale", revision("vale", corrected = "¡¿Vale", natural = "Oye ¡¿Vale"))
        assertAcceptingAllGives(",:x", revision("x", corrected = ",:x", natural = "?,:x"))
    }

    @Test
    fun insertionsTouchingCorrectedWordingAreWithheldWithoutAbsorbingThatCorrection() {
        for ((original, corrected, natural) in listOf(
            Triple("Oye", "bien Oye", "sí bien Oye"), Triple("!", "E !", "q E !"),
            Triple(", hola", ",", ", adiós"),
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = corrected, moreNatural = true, natural = natural,
                naturalChanges = listOf(VerdictChange(corrected, natural)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
            assertEquals(setOf(EditKind.Natural), result.withheld)
            assertEquals(corrected, result.revision.acceptAll(EditKind.Fix).workingText)
            assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test
    fun aWordWrittenWithASeparateAccentMarkIsTheSameWord() {
        // "café" with "e" + U+0301, as text pasted from macOS often has it, against the composed "é".
        val decomposed = "cafe\u0301 bo"
        assertTrue(revision(decomposed, corrected = "café bo", natural = "café bo").edits.isEmpty())

        val revision = revision(decomposed, corrected = "café bueno", natural = "Un café bueno",
            naturalChanges = listOf(VerdictChange("", "Un ")))
        assertEquals(listOf("bo" to "bueno"), revision.changes(EditKind.Fix))
        assertEquals(decomposed.indexOf("bo"), revision.edits(EditKind.Fix).single().start)
        assertEquals(listOf("" to "Un "), revision.changes(EditKind.Natural))
        assertEquals("Un cafe\u0301 bueno", revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun aLineBreakIsNeverSplit() {
        val revision = revision("a\r\nb", corrected = "a\r\nx\r\nb")
        for (edit in revision.edits) {
            for (at in listOf(edit.start, edit.end)) assertFalse("$edit", "a\r\nb".getOrNull(at - 1) == '\r' && "a\r\nb".getOrNull(at) == '\n')
        }
        assertEquals("a\r\nx\r\nb", revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun aReportedRewordingNeverSplitsACharacter() {
        val original = "hola 👨‍👩‍👧 adiós"
        val safe = revision(original, natural = "hola 👨‍👩‍👦 adiós",
            naturalChanges = listOf(VerdictChange("👨‍👩‍👧", "👨‍👩‍👦")))
        assertEquals(listOf("👨‍👩‍👧" to "👨‍👩‍👦"), safe.changes(EditKind.Natural))

        // Invalid reports are withheld instead of inventing a broader fallback change.
        val partial = revision(original, natural = "hola 👨‍👩‍👦 adiós",
            naturalChanges = listOf(VerdictChange("👧", "👦")))
        assertTrue(partial.edits(EditKind.Natural).isEmpty())
        val split = revision("😀 hola", natural = "X😀 hola",
            naturalChanges = listOf(VerdictChange("\uD83D", "X\uD83D")))
        assertTrue(split.edits(EditKind.Natural).isEmpty())
        assertEquals("😀 hola", split.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun replacingPunctuationWithSpaceIsOneCompleteRepair() {
        val r = revision("Llegeixo el:llibre", corrected = "Llegeixo el llibre")
        assertEquals(listOf(":" to " "), r.changes(EditKind.Fix))
        assertEquals("Llegeixo el llibre", r.accept(r.edits.single().id).workingText)
        assertEquals("Llegeixo el:llibre", r.acceptAll(EditKind.Fix).undo().workingText)
    }

    @Test
    fun anIncompleteReportCannotSplitASpellingRepairFromAnAdjacentDeletion() {
        // Live ca-027: the report list deliberately omits the added sentence stop.
        val original = "Les fotos del viat ge ahnquedat molt bé Te les enviraé aquesta demà."
        val corrected = "Les fotos del viatge han quedat molt bé. Te les enviaré demà al matí."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected, fixes = listOf(
            VerdictChange("viat ge", "viatge", "Merged split word"),
            VerdictChange("ahnquedat", "han quedat", "Typo and missing space"),
            VerdictChange("enviraé", "enviaré", "Spelling error"),
            VerdictChange("aquesta demà", "demà al matí", "Correct expression for tomorrow morning"),
        ))) as CheckResult.Reviewed
        val r = result.revision
        assertTrue(EditKind.Fix in result.withheld)
        for (edit in r.edits(EditKind.Fix)) {
            assertTrue(r.accept(edit.id).workingText.endsWith("Te les enviraé aquesta demà."))
            assertEquals(original, r.accept(edit.id).undo().workingText)
        }
        assertEquals("Les fotos del viatge ahnquedat molt bé. Te les enviraé aquesta demà.",
            r.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun aMalformedReportCannotLicenseCapitalisationAndAnArticleInOneFix() {
        // Live sp-018: preserve the malformed empty-source vocative report and
        // missing final-period report. Neither may invent an alignment constraint.
        val original = "abuelo niños salen del colegio a las cinco. Hoy los ira a recoger su abuelo. Yo los esperar'é en ver"
        val corrected = "Abuelo, los niños salen del colegio a las cinco. Hoy los irá a recoger su abuelo. Yo los esperaré en casa."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected, fixes = listOf(
            VerdictChange("", "Abuelo,", "Added vocative comma"),
            VerdictChange("niños", "los niños", "Added missing article"),
            VerdictChange("ira", "irá", "Missing accent"),
            VerdictChange("esperar'é", "esperaré", "Removed typo"),
            VerdictChange("en ver", "en casa", "Corrected typo"),
        ))) as CheckResult.Reviewed
        val r = result.revision
        assertTrue(EditKind.Fix in result.withheld)
        assertTrue(r.edits(EditKind.Fix).all { r.accept(it.id).workingText.startsWith("abuelo niños salen") })
        assertEquals("abuelo niños salen del colegio a las cinco. Hoy los irá a recoger su abuelo. Yo los esperaré en casa.",
            r.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun aBroadReportDoesNotBundleIndependentAccents() {
        val original = "Parlo catala i espanol."
        val corrected = "Parlo català i español."
        val r = revision(original, corrected,
            fixChanges = listOf(VerdictChange(original, corrected, "Missing accents")))
        val fixes = r.edits(EditKind.Fix)
        assertEquals(listOf("catala" to "català", "espanol" to "español"), r.changes(EditKind.Fix))
        assertEquals("Parlo català i espanol.", r.accept(fixes[0].id).workingText)
        assertEquals("Parlo catala i español.", r.accept(fixes[1].id).workingText)
        assertAcceptingAllGives(corrected, r)
    }

    @Test
    fun anIncompleteWordReportCannotSplitACompleteLexicalRepair() {
        val r = revision("Parlo ca tala.", "Parlo català.",
            fixChanges = listOf(VerdictChange("tala", "talà", "Missing accent")))
        assertEquals(listOf("ca tala" to "català"), r.changes(EditKind.Fix))
        assertEquals("Parlo català.", r.accept(r.edits.single().id).workingText)
    }

    @Test
    fun partialReportsCannotCutMechanicalWordRepairs() {
        for ((from, to, report) in listOf(
            Triple("bo nic", "bonic", VerdictChange("nic", "bonic")),
            Triple("L es", "Les", VerdictChange("L", "Les")),
            Triple("petit;a", "petita", VerdictChange("petit", "petita")),
            Triple("Ar a", "Ara", VerdictChange("Ar", "Ara")),
            Triple("hum\n\nida", "humida", VerdictChange("hum", "humida")),
        )) {
            val r = revision(from, to, fixChanges = listOf(report))
            assertEquals(listOf(from to to), r.changes(EditKind.Fix))
            assertEquals(to, r.accept(r.edits.single().id).workingText)
            assertEquals(from, r.acceptAll(EditKind.Fix).undo().workingText)
        }
    }

    @Test
    fun opaqueBrokenWordRunsAreWithheldEvenWhenTheirWordCountsAgree() {
        val original = "Jo el s mai a casa."
        for (replacement in listOf("els tinc", "no els tinc")) {
            val corrected = "Jo $replacement mai a casa."
            for (reported in listOf(emptyList(), listOf(VerdictChange("el s", replacement)))) {
                val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                    corrected = corrected, fixes = reported)) as CheckResult.Reviewed
                assertTrue(result.revision.edits.isEmpty())
                assertEquals(original, result.revision.acceptAll(EditKind.Fix).workingText)
                assertTrue(EditKind.Fix in result.withheld)
                assertFalse(result.looksGood)
                assertFalse(result.isResolved)
            }
        }
    }

    @Test
    fun malformedLivePronounReportsDoNotExposePartialWordRepairs() {
        val original = "Els nens surten de l'escola a hles cinc. Avui els anira a carrer el seu avi. Jo  el s mai a casa."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Els nens surten de l'escola a les cinc. Avui els anirà a buscar el seu avi. Jo no els tinc mai a casa.",
            fixes = listOf(
                VerdictChange("hles", "les", "Typo in 'les'"),
                VerdictChange("anira", "anirà", "Missing accent"),
                VerdictChange("carrer", "buscar", "Incorrect verb choice"),
                VerdictChange("el s", "no els", "Spacing and missing negation"),
                VerdictChange("mai", "tinc", "Missing verb"),
            ))) as CheckResult.Reviewed
        val r = result.revision
        assertTrue(EditKind.Fix in result.withheld)
        assertTrue(r.edits(EditKind.Fix).any { it.from == "anira" && it.replacement == "anirà" })
        for (fix in r.edits(EditKind.Fix)) assertTrue(r.accept(fix.id).workingText.endsWith("Jo  el s mai a casa."))
        assertTrue(r.acceptAll(EditKind.Fix).workingText.endsWith("Jo  el s mai a casa."))
    }

    @Test
    fun aVerbRepairAndAnAddedPrepositionCannotShareOneAccept() {
        val original = "La informació sé taula htps://examle.o rg/horaris. Consulta-la abans de sortir. E ls horaris poden canviar."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "La informació és a taula htps://examle.o rg/horaris. Consulta-la abans de sortir. Els horaris poden canviar.",
            fixes = listOf(VerdictChange("sé", "és a", "Verb form and preposition"),
                VerdictChange("E ls", "Els", "Spacing correction")))) as CheckResult.Reviewed
        assertTrue(EditKind.Fix in result.withheld)
        assertEquals(listOf("E ls" to "Els"), result.revision.changes(EditKind.Fix))
        assertEquals("La informació sé taula htps://examle.o rg/horaris. Consulta-la abans de sortir. Els horaris poden canviar.",
            result.revision.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun crossedWordsAreWithheldTogetherWhileIndependentFixesRemainUsable() {
        val original = "El uelo llegga a las 2130. Julia nos esperará a la salwida. La avisaré pero hay abuelo retraso."
        val corrected = "El vuelo llega a las 21:30. Julia nos esperará a la salida. La avisaré pero hay retraso, abuelo."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected,
            fixes = listOf(VerdictChange("uelo", "vuelo", "Missing letter v"), VerdictChange("llegga", "llega", "Spelling error"),
                VerdictChange("2130", "21:30", "Time format"), VerdictChange("salwida", "salida", "Spelling error"),
                VerdictChange("pero hay abuelo retraso", "pero hay retraso, abuelo", "Word order and comma")))) as CheckResult.Reviewed
        val r = result.revision
        assertEquals(listOf("uelo" to "vuelo", "llegga" to "llega", "2130" to "21:30", "salwida" to "salida"), r.changes(EditKind.Fix))
        assertTrue(EditKind.Fix in result.withheld)
        for (fix in r.edits(EditKind.Fix)) {
            assertTrue(r.accept(fix.id).workingText.endsWith("hay abuelo retraso."))
            assertEquals(original, r.accept(fix.id).undo().workingText)
        }
        assertEquals("El vuelo llega a las 21:30. Julia nos esperará a la salida. La avisaré pero hay abuelo retraso.",
            r.acceptAll(EditKind.Fix).workingText)
    }

    @Test
    fun exactLocalReportsCanCorroborateAlreadyDerivedIndependentWords() {
        val original = "El uelo llegga hoy."
        val corrected = "El vuelo llega hoy."
        val r = revision(original, corrected,
            fixChanges = listOf(VerdictChange("uelo", "vuelo"), VerdictChange("llegga", "llega")))
        val fixes = r.edits(EditKind.Fix)
        assertEquals(listOf("El vuelo llegga hoy.", "El uelo llega hoy."), fixes.map { r.accept(it.id).workingText })
        assertAcceptingAllGives(corrected, r)
        // This valid adjacent correction has the same unreported shape as el s -> els tinc.
        // A broad or missing report cannot establish the two intended boundaries.
        for (reported in listOf(emptyList(), listOf(VerdictChange("uelo llegga", "vuelo llega")))) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = corrected, fixes = reported)) as CheckResult.Reviewed
            assertTrue(result.revision.edits.isEmpty())
            assertTrue(EditKind.Fix in result.withheld)
            assertEquals(original, result.revision.acceptAll(EditKind.Fix).workingText)
        }
    }

    @Test
    fun aSingleCompleteWordRepairSurvivesRepeatedUnchangedContext() {
        val original = "Cuando la clase termcine, la revisaré."
        val corrected = "Cuando la clase termine, la revisaré."
        val r = revision(original, corrected)
        assertEquals(listOf("termcine" to "termine"), r.changes(EditKind.Fix))
        assertEquals(corrected, r.accept(r.edits.single().id).workingText)
        assertEquals(original, r.acceptAll(EditKind.Fix).undo().workingText)
    }

    @Test
    fun anIncompleteReportListCanCorroborateExactIndependentTypos() {
        val original = "El uelo llegga hoy"
        val corrected = "El vuelo llega hoy."
        // The final period is absent from the list. Each word report still identifies
        // exactly one existing source and target unit; it does not establish a cut.
        val r = revision(original, corrected,
            fixChanges = listOf(VerdictChange("uelo", "vuelo"), VerdictChange("llegga", "llega")))
        assertEquals(listOf("El vuelo llegga hoy", "El uelo llega hoy", "El uelo llegga hoy."),
            r.edits(EditKind.Fix).map { r.accept(it.id).workingText })
        assertAcceptingAllGives(corrected, r)
    }

    @Test
    fun partialReportsCannotChooseBetweenRepeatedSourceOrTargetWords() {
        for ((original, corrected, reports) in listOf(
            Triple("ca ca", "casa cosa.", listOf(VerdictChange("ca", "casa"), VerdictChange("ca", "cosa"))),
            Triple("cax cas", "casa casa.", listOf(VerdictChange("cax", "casa"), VerdictChange("cas", "casa"))),
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = corrected, fixes = reports)) as CheckResult.Reviewed
            assertTrue(result.revision.edits.isEmpty())
            assertTrue(EditKind.Fix in result.withheld)
            assertEquals(original, result.revision.acceptAll(EditKind.Fix).workingText)
        }
    }

    @Test
    fun aReasonWithDifferentWhitespaceCannotDescribeOnlyTheLexicalPart() {
        for ((original, corrected, change) in listOf(
            Triple("Feia\r\n olt de temps", "Feia molt de temps",
                VerdictChange("\n olt", "molt", "Fixing line break and missing letter")),
            Triple("J\u2028 tinc els bitllets", "Jo tinc els bitllets",
                VerdictChange("J\n", "Jo", "Fix broken word and spelling")),
        )) {
            val r = revision(original, corrected, fixChanges = listOf(change))
            assertTrue(r.edits(EditKind.Fix).all { it.why == null })
            assertEquals(corrected, r.acceptAll(EditKind.Fix).workingText)
        }
        val exact = revision("olt de temps", "molt de temps",
            fixChanges = listOf(VerdictChange("olt", "molt", "Missing letter")))
        assertEquals("Missing letter", exact.edits.single().why)
    }

    @Test
    fun numericSeparatorRepairsKeepTheCompleteNumberInOneAction() {
        val r = revision("Costa 2,:5 euros.", corrected = "Costa 2.5 euros.")
        assertEquals(listOf("2,:5" to "2.5"), r.changes(EditKind.Fix))
        assertEquals("Costa 2.5 euros.", r.accept(r.edits.single().id).workingText)
        assertEquals("Costa 2,:5 euros.", r.acceptAll(EditKind.Fix).undo().workingText)
    }

    @Test
    fun aReportedBoundaryAndItsInitialCaseAreOneCorrection() {
        val original = "Hoy llueve. Vamos tarde."
        val corrected = "Hoy llueve, vamos tarde."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = corrected, fixes = listOf(VerdictChange(". Vamos", ", vamos")))) as CheckResult.Reviewed
        assertEquals(listOf(". Vamos" to ", vamos"), result.revision.changes(EditKind.Fix))
        assertEquals(corrected, result.revision.accept(result.revision.edits.single().id).workingText)
        assertEquals(original, result.revision.acceptAll(EditKind.Fix).undo().workingText)
        assertTrue(result.withheld.isEmpty())
    }

    @Test
    fun malformedBoundaryReportsDoNotOfferALowercaseWordAfterTheOldPeriod() {
        // Keep the saved broken-word witness and a supported one-word typo. The
        // first requires an unproved join; the second remains independently usable.
        for ((broken, offered) in listOf("núe;ro" to "núe;ro", "núemro" to "número")) {
            val original = "La factura es de 125 pero. Tenemos que pagarla antes del viernes.  Ya he comprobado el $broken de cuenta."
            val corrected = "La factura es de 125, pero tenemos que pagarla antes del viernes. Ya he comprobado el número de cuenta."
            for (changes in listOf(emptyList(), listOf(VerdictChange(". Tenemos", ", tenemos"),
                VerdictChange(broken, "número")))) {
                val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                    corrected = corrected, fixes = changes)) as CheckResult.Reviewed
                val revision = result.revision
                assertTrue(EditKind.Fix in result.withheld)
                assertFalse(result.looksGood)
                assertEquals(offered != broken, revision.edits(EditKind.Fix).any { it.from == broken && it.replacement == "número" })
                for (fix in revision.edits(EditKind.Fix)) {
                    assertTrue(revision.accept(fix.id).workingText.contains("pero. Tenemos"))
                }
                val accepted = revision.acceptAll(EditKind.Fix)
                assertEquals("La factura es de 125, pero. Tenemos que pagarla antes del viernes. Ya he comprobado el $offered de cuenta.",
                    accepted.workingText)
                assertFalse(result.copy(revision = accepted).isResolved)
            }
        }
    }

    @Test
    fun unchangedReportContextDoesNotOwnAnUnrelatedSpellingCorrection() {
        val original = "Voy a tomar una ducha y te llamo despues."
        val corrected = "Voy a tomar una ducha y te llamo después."
        val natural = "Voy a ducharme y te llamo después."
        val r = revision(original, corrected, natural,
            naturalChanges = listOf(VerdictChange(corrected, natural)))
        val rewording = r.edits(EditKind.Natural).single()
        assertEquals("tomar una ducha", rewording.from)
        assertEquals("ducharme", rewording.replacement)
        assertTrue(rewording.includes.isEmpty())
        val accepted = r.accept(rewording.id)
        assertEquals("Voy a ducharme y te llamo despues.", accepted.workingText)
        assertEquals(listOf("despues" to "después"), accepted.changes(EditKind.Fix))
        assertAcceptingAllGives(natural, r)
        assertEquals(original, accepted.undo().workingText)
    }

    @Test
    fun anInsertionDependingOnCorrectedContextIsWithheld() {
        val original = "Está encima dei comedor."
        val corrected = "Está encima del comedor."
        val natural = "Está encima de la mesa del comedor."
        val changes = listOf(VerdictChange("del comedor", "de la mesa del comedor"))
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = corrected, moreNatural = true, natural = natural,
            naturalChanges = changes)) as CheckResult.Reviewed
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        assertEquals(setOf(EditKind.Natural), result.withheld)
        assertEquals(corrected, result.revision.acceptAll(EditKind.Fix).workingText)
        assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        assertFalse(result.looksGood)

        val independent = revision(corrected, natural = natural, naturalChanges = changes)
        assertEquals(listOf("" to "de la mesa "), independent.changes(EditKind.Natural))
        assertEquals(natural, independent.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun anOpaqueCorrectionCannotLicenseItsDependentRewording() {
        val original = "Está encima de la comer."
        val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = "Está encima del comedor.", moreNatural = true, natural = "Está encima de la mesa del comedor.",
            naturalChanges = listOf(VerdictChange("del comedor", "de la mesa del comedor")))) as CheckResult.Reviewed
        assertEquals(setOf(EditKind.Fix, EditKind.Natural), result.withheld)
        assertTrue(result.revision.edits.isEmpty())
        assertEquals(original, result.revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText)
        assertFalse(result.isResolved)
    }

    @Test
    fun aRewordingCannotGuessOwnershipOfAnAdjacentDeletedWord() {
        for ((original, corrected, natural, from, to) in listOf(
            listOf("Algunas algunas cosas", "Algunas cosas", "Unas cosas", "Algunas", "Unas"),
            listOf("algunas Algunas cosas", "Algunas cosas", "Unas cosas", "Algunas", "Unas"),
            listOf("algunas algunas cosas", "algunas cosas", "unas cosas", "algunas", "unas"),
        )) {
            val result = interpret(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
                corrected = corrected, moreNatural = true, natural = natural,
                naturalChanges = listOf(VerdictChange(from, to)))) as CheckResult.Reviewed
            assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
            assertTrue(EditKind.Natural in result.withheld)
            assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
            assertFalse(result.looksGood)
        }
    }

    @Test
    fun deletionDependencyIsCheckedAgainAfterWideningToACorrection() {
        val original = "fooBar other Other"
        val fixes = listOf(
            Edit(0, EditKind.Fix, 0, 6, "fooBar", "foo Bar"),
            Edit(1, EditKind.Fix, 6, 12, " other", ""),
        )
        val result = Edits.naturalResult(original, fixes, "baz Bar Other", listOf(VerdictChange("foo", "baz")))
        assertTrue(result.edits.isEmpty())
        assertTrue(result.rejected)
    }

    @Test
    fun anUnrelatedRewordingDoesNotInheritADeletedWord() {
        val original = "Algunas algunas cosas son buenas."
        val corrected = "Algunas cosas son buenas."
        val natural = "Algunas cosas son estupendas."
        val r = revision(original, corrected, natural,
            naturalChanges = listOf(VerdictChange("buenas", "estupendas")))
        val naturalEdit = r.edits(EditKind.Natural).single()
        assertEquals("Algunas algunas cosas son estupendas.", r.accept(naturalEdit.id).workingText)
        assertTrue(naturalEdit.includes.isEmpty())
        assertAcceptingAllGives(natural, r)
        assertEquals(original, r.accept(naturalEdit.id).undo().workingText)
    }

    @Test
    fun aRepeatedWordIsPlacedByTheCompleteTargetWithoutChangingTheOtherOccurrence() {
        val original = "És bonic. També és bonic."
        val target = "És bonic. També és preciós."
        val r = revision(original, natural = target,
            naturalChanges = listOf(VerdictChange("bonic", "preciós")))
        val edit = r.edits(EditKind.Natural).single()
        assertEquals(original.lastIndexOf("bonic"), edit.start)
        assertEquals(target, r.accept(edit.id).workingText)
        assertEquals(original, r.accept(edit.id).undo().workingText)
    }

    @Test
    fun anExplicitInsertionCanBePlacedWithoutBorrowingAnUnchangedWord() {
        val r = revision("T'ho agraeixo", natural = "T'ho agraeixo molt",
            naturalChanges = listOf(VerdictChange("", " molt")))
        assertEquals(listOf("" to " molt"), r.changes(EditKind.Natural))
        assertEquals("T'ho agraeixo molt", r.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun supplementaryDigitsCannotBeChangedByARewording() {
        val original = "Vindran 𝟚 persones"
        val r = revision(original, natural = "Vindran 𝟛 convidats",
            naturalChanges = listOf(VerdictChange("𝟚 persones", "𝟛 convidats")))
        assertTrue(r.edits(EditKind.Natural).isEmpty())
        assertEquals(original, r.acceptAll(EditKind.Natural).workingText)
    }

    @Test
    fun noSuggestionAddsALink() {
        val original = "escribeme mañana"
        val fixes = revision(original, corrected = "Escríbeme mañana en evil.com").changes(EditKind.Fix)
        assertEquals(listOf("escribeme" to "Escríbeme"), fixes)
        assertEquals(emptyList<Any>(), revision(original, corrected = "escribeme mañana https://x.io/a").edits)
        assertEquals(emptyList<Any>(), revision(original, natural = "Escríbeme a www.ejemplo.net mañana").edits)
        // Pieces of an address inserted as separate fixes.
        assertEquals(emptyList<Any>(), revision("hola", corrected = "hola evil.com").edits)
        assertEquals(emptyList<Any>(), revision("hola evil com", corrected = "hola evil.com").edits)
        // Safety concerns the offered actions, not an obsolete fragmentation of this repair.
        val mixed = revision("hola evil 1", corrected = "hola evi!.com")
        for (mask in 1 until (1 shl mixed.edits.size)) {
            val accepted = mixed.edits.filterIndexed { i, _ -> mask shr i and 1 == 1 }
                .fold(mixed) { current, edit -> current.accept(edit.id) }
            assertFalse(Links.hasNewLink(mixed.original, accepted.workingText))
        }

        // A link already in the text, and abbreviations, are fine.
        assertEquals(
            listOf("mira" to "Mira", "" to "."),
            revision("mira https://ejemplo.com", corrected = "Mira https://ejemplo.com.").changes(EditKind.Fix),
        )
        assertEquals("vivo en EE.UU.", revision("vivo en EE UU", corrected = "vivo en EE.UU.").preview(EditKind.Fix).text)
        // Words a missing space joins look like an address, but fixing their accents adds none.
        assertEquals("estás. Bien", revision("estas.Bien", corrected = "estás. Bien").preview(EditKind.Fix).text)
    }
}
