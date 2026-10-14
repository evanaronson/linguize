package com.evanaronson.linguize.core

import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hard gates for the audit's corrupting Accept actions, independent of a live model. */
class RepairRegressionTest {
    private val prompt = Prompt(system = "", schemaJson = "{}")

    private fun fixtureFile(name: String) = File("../eval/fuzz", name)

    private fun reviewed(original: String, verdict: Verdict, judgments: Judgments = Judgments.Both) =
        interpret(original, verdict, judgments) as CheckResult.Reviewed

    private fun fixes(original: String, corrected: String) = reviewed(original,
        Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = corrected)).revision

    @Test fun allSevenFrozenFailuresOfferCompleteRepairsOrExplicitlyWithholdUnsupportedRuns() {
        val fixtures = Json.parseToJsonElement(fixtureFile("confirmed_engine_failures.json").readText()).jsonArray
        assertEquals("Every confirmed witness needs a hand-reviewed oracle", expectedFixes.keys,
            fixtures.map { it.jsonObject.getValue("request_id").jsonPrimitive.content }.toSet())
        for (item in fixtures) {
            val fixture = item.jsonObject
            val id = fixture.getValue("request_id").jsonPrimitive.content
            val original = fixture.getValue("original").jsonPrimitive.content
            val verdict = prompt.parseVerdict(fixture.getValue("verdict").toString())
            // Mechanical joins still work with absent/misaligned reports (including
            // the saved CRLF case). Adjacent lexical substitutions require evidence.
            for (answer in listOf(verdict, verdict.copy(fixes = emptyList()))) {
                val complete = expectedFixes.getValue(id)
                val expected = if (id == "sp-012:auto" && answer.fixes.isEmpty()) ExpectedFixes(
                    "El uelo llegga a las 2130. Julia nos esperará a la salida. La avisaré si hay algún retraso.",
                    complete.single.drop(2), // uelo/llegga are separate only in the exact reports.
                    withheld = true,
                ) else complete
                val result = reviewed(original, answer)
                val revision = result.revision
                if (id == "ca-005:auto") {
                    // Construction must produce four independent repairs. Publication
                    // still protects a bare address: sofà.Si would be a new domain until
                    // the independent boundary-space repair has been accepted.
                    val derived = Edits.fixes(original, answer.corrected, answer.fixes)
                    assertIndependentFixes(id, Revision(original, derived.edits), expected)
                    assertIndependentFixes("$id safe subset", revision, ExpectedFixes(
                        "El gos s'ha adormit alsofà. Si truques al timbre, es despertarà.",
                        expected.single.filterNot { "al sofà.Si" in it },
                    ))
                    assertTrue(EditKind.Fix in result.withheld)
                    assertFalse(result.looksGood)
                } else {
                    assertIndependentFixes(id, revision, expected)
                    assertEquals("$id: withheld correctness must remain visible", expected.withheld,
                        EditKind.Fix in result.withheld)
                    if (expected.withheld) {
                        assertFalse(result.looksGood)
                        assertFalse(result.isResolved)
                    }
                }
                for (edit in revision.edits(EditKind.Natural)) {
                    val accepted = revision.accept(edit.id).workingText
                    assertEquals("$id: rewording changed numeric facts", original.filter(Char::isDigit),
                        accepted.filter(Char::isDigit))
                }
            }
        }
    }

    /** Every expected sentence is written below, never constructed from the actual edit. */
    private fun assertIndependentFixes(id: String, revision: Revision, expected: ExpectedFixes) {
        val edits = revision.edits(EditKind.Fix)
        assertEquals("$id: each Accept must be one complete repair", expected.single.sorted(),
            edits.map { revision.accept(it.id).workingText }.sorted())
        assertEquals("$id: preview", expected.all, revision.preview(EditKind.Fix).text)
        assertEquals("$id: Accept all", expected.all, revision.acceptAll(EditKind.Fix).workingText)
        assertEquals("$id: Undo Accept all", revision.original, revision.acceptAll(EditKind.Fix).undo().workingText)
        for (edit in edits) {
            val single = revision.accept(edit.id)
            assertEquals("$id: Undo individual Accept", revision.original, single.undo().workingText)
            assertEquals("$id: duplicate Accept", single, single.accept(edit.id))
            assertEquals("$id: Accept remaining", expected.all, single.acceptAll(EditKind.Fix).workingText)
        }
        val ids = edits.map { it.id }
        val interleaved = ids.filterIndexed { index, _ -> index % 2 == 0 } +
            ids.filterIndexed { index, _ -> index % 2 != 0 }
        for (order in listOf(ids, ids.reversed(), interleaved)) {
            val accepted = order.fold(revision) { current, edit -> current.accept(edit) }
            assertEquals("$id: acceptance order $order", expected.all, accepted.workingText)
            assertEquals("$id: Undo every action", revision.original,
                order.fold(accepted) { current, _ -> current.undo() }.workingText)
        }
    }

    @Test fun timeFormattingIsOneCorrectnessActionAndNeverARewording() {
        val original = "El vuelo llega a las 2130. Julia esperará."
        val formatted = "El vuelo llega a las 21:30. Julia esperará."
        assertIndependentFixes("time formatting", fixes(original, formatted),
            ExpectedFixes(formatted, listOf(formatted)))
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok,
            moreNatural = true, natural = formatted,
            naturalChanges = listOf(VerdictChange("2130", "21:30", "Time formatting"))))
        assertTrue(result.revision.edits(EditKind.Natural).isEmpty())
        assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
    }

    @Test fun theUsersBundledObligationAndVerbChangeIsWithheldWithoutInventingAFix() {
        val fixture = Json.parseToJsonElement(fixtureFile("user_atomicity_case.json").readText()).jsonObject
        val original = fixture.getValue("original").jsonPrimitive.content
        val saved = prompt.parseVerdict(fixture.getValue("verdict").toString())
        for (reason in listOf(saved.naturalChanges.single().why, "", "More natural", "Expressa millor la idea")) {
            val verdict = saved.copy(naturalChanges = saved.naturalChanges.map { it.copy(why = reason) })
            val result = reviewed(original, verdict)
            assertTrue("A reason's wording cannot make this bundle atomic", result.revision.edits.isEmpty())
            assertFalse("Rejected suggestions must not mean looks natural", result.looksGood)
            assertEquals(original, result.revision.acceptAll(EditKind.Natural).workingText)
        }
    }

    @Test fun theUsersObligationCanBeCorrectedWithoutReplacingTheValidVerb() {
        val fixture = Json.parseToJsonElement(fixtureFile("user_atomicity_case.json").readText()).jsonObject
        val original = fixture.getValue("original").jsonPrimitive.content
        val corrected = fixture.getValue("expected_corrected").jsonPrimitive.content
        val reported = fixture.getValue("expected_fixes").jsonArray.map { item ->
            val change = item.jsonObject
            VerdictChange(change.getValue("from").jsonPrimitive.content,
                change.getValue("to").jsonPrimitive.content, change.getValue("why").jsonPrimitive.content)
        }
        // This is the hand-authored good answer, separate from the saved model failure.
        // Rejecting the bad answer alone must not disable a legitimate grammatical repair.
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok,
            hasErrors = true, corrected = corrected, fixes = reported))
        assertIndependentFixes("obligation without verb preference", result.revision,
            ExpectedFixes(corrected, listOf(corrected)))
        assertEquals(fixture.getValue("expected_natural_edit_count").jsonPrimitive.content.toInt(),
            result.revision.edits(EditKind.Natural).size)

        // Full-text alignment alone cannot show that these grammatical parts depend
        // on one another. It must neither invent that grouping nor expose fragments.
        val unreported = reviewed(original, Verdict(status = Verdict.Status.Ok,
            hasErrors = true, corrected = corrected))
        assertTrue("Do not offer incomplete grammatical fragments", unreported.revision.edits(EditKind.Fix).isEmpty())
        assertEquals(original, unreported.revision.acceptAll(EditKind.Fix).workingText)
        assertTrue("An unprovable correction must be marked incomplete", EditKind.Fix in unreported.withheld)
        assertFalse(unreported.looksGood)
        assertFalse(unreported.isResolved)
    }

    @Test fun explicitRewordingsStayInsideTheirSentenceAndRemainIndependent() {
        val original = "Voy a tomar una ducha. Voy a hacer una llamada."
        val natural = "Voy a ducharme. Voy a llamar."
        val changes = listOf(VerdictChange("tomar una ducha", "ducharme", "More usual expression"),
            VerdictChange("Voy a hacer una llamada", "Voy a llamar", "More direct expression"))
        val result = reviewed(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = natural, naturalChanges = changes))
        val revision = result.revision
        assertEquals(listOf("Voy a ducharme. Voy a hacer una llamada.", "Voy a tomar una ducha. Voy a llamar."),
            revision.edits(EditKind.Natural).map { revision.accept(it.id).workingText })
        assertEquals(2, revision.edits(EditKind.Natural).size)
        assertEquals(natural, revision.acceptAll(EditKind.Natural).workingText)
        assertEquals(original, revision.acceptAll(EditKind.Natural).undo().workingText)
        for (order in listOf(revision.edits(EditKind.Natural), revision.edits(EditKind.Natural).reversed())) {
            val accepted = order.fold(revision) { current, edit -> current.accept(edit.id) }
            assertEquals("Separate phrase choices must work in either order", natural, accepted.workingText)
            assertEquals(original, accepted.undo().undo().workingText)
        }

        val bundled = reviewed(original, Verdict(status = Verdict.Status.Ok, moreNatural = true,
            natural = natural, naturalChanges = listOf(VerdictChange(original, natural, "More usual expression"))))
        assertTrue("A reported item cannot span the period", bundled.revision.edits(EditKind.Natural).isEmpty())
        assertFalse(bundled.looksGood)
    }

    @Test fun overlappingLineBreakFixAndOnePhraseRewordingKeepUnrelatedCorrectionsIndependent() {
        val original = "Voy a tomar\r\nuna ducha. Te llamo despues a las 21:30."
        val corrected = "Voy a tomar una ducha. Te llamo después a las 21:30."
        val natural = "Voy a ducharme. Te llamo después a las 21:30."
        val revision = reviewed(original, Verdict(status = Verdict.Status.Ok, hasErrors = true,
            corrected = corrected, moreNatural = true, natural = natural,
            naturalChanges = listOf(VerdictChange("tomar una ducha", "ducharme", "More usual expression")))).revision
        val rewording = revision.edits(EditKind.Natural).single()
        val naturalOnly = revision.accept(rewording.id)
        assertEquals("Voy a ducharme. Te llamo despues a las 21:30.", naturalOnly.workingText)
        assertEquals(listOf("después"), naturalOnly.remaining(EditKind.Fix).map { it.replacement })
        assertEquals(original, naturalOnly.undo().workingText)
        for (order in listOf(EditKind.entries, EditKind.entries.reversed())) {
            val accepted = order.fold(revision) { current, kind -> current.acceptAll(kind) }
            assertEquals(natural, accepted.workingText)
            assertEquals(original, accepted.undo().undo().workingText)
        }
    }

    @Test fun spacingRepairBesideUnicodeAndARealLinkPreservesDeliberateParagraphs() {
        val original = "Cafe\u0301 ☕: escri\r\nbeme a https://café.example/Account?code=AbC.\r\n\r\n- pa\r\n- llet"
        val corrected = "Café ☕: escríbeme a https://café.example/Account?code=AbC.\r\n\r\n- pa\r\n- llet"
        val expected = "Cafe\u0301 ☕: escríbeme a https://café.example/Account?code=AbC.\r\n\r\n- pa\r\n- llet"
        assertIndependentFixes("Unicode and real link", fixes(original, corrected), ExpectedFixes(expected, listOf(expected)))
        val changedLink = fixes(original, corrected.replace("code=AbC", "code=abc"))
        assertEquals("A correction must not alter the existing destination", expected,
            changedLink.acceptAll(EditKind.Fix).workingText)
    }

    @Test fun lineBreakRepairsInsideAndBetweenWordsAreIndependentForLfAndCrlf() {
        for (lineBreak in listOf("\n", "\r\n")) {
            val original = "Esta frase sa${lineBreak}le mal. La otra${lineBreak}frase queda igual."
            val corrected = "Esta frase sale mal. La otra frase queda igual."
            assertIndependentFixes("line breaks ${lineBreak.length}", fixes(original, corrected),
                ExpectedFixes(corrected, listOf(
                    "Esta frase sale mal. La otra${lineBreak}frase queda igual.",
                    "Esta frase sa${lineBreak}le mal. La otra frase queda igual.",
                )))
        }
    }

    @Test fun providersSettingsAndSavedAnswerReuseShareTheSameSafetyGate() = runBlocking {
        val original = "El vuelo llega a las 2130. Julia espera."
        val raw = """{"status":"ok","language":"Spanish","has_errors":true,"corrected":"El vuelo llega a las 21:30. Julia espera.","more_natural":true,"natural":"El vuelo llega a las 21:30. Julia aguarda.","natural_changes":[{"from":"espera","to":"aguarda","why":"More formal wording"}]}"""
        val verdict = prompt.parseVerdict(raw)
        for (provider in Provider.entries) for (punctuation in Punctuation.entries)
            for (judgments in Judgments.entries) for (language in listOf(Language.Spanish, null)) {
            val preferences = FakePreferences().apply {
                this.provider = provider
                this.punctuation = punctuation
                this.judgments = judgments
            }
            val client = FakeClient { ModelAnswer(verdict, raw) }
            val checker = fakeChecker(preferences, client, prompt = prompt)
            val check = checker.check(original, language)
            assertEquals(check.result, checker.reuse(original, language, raw, check.context).result)
            val result = check.result as CheckResult.Reviewed
            assertEquals(judgments.kinds, result.kinds.toSet())
            assertEquals(if (EditKind.Fix in judgments.kinds) "El vuelo llega a las 21:30. Julia espera." else original,
                result.revision.acceptAll(EditKind.Fix).workingText)
            for (kind in result.kinds) for (edit in result.revision.edits(kind)) {
                assertEquals("The numeric fact survives every offered action", "2130",
                    result.revision.accept(edit.id).workingText.filter(Char::isDigit))
            }
            assertEquals(punctuation, client.checks.first().third.punctuation)
            assertEquals(judgments, client.checks.first().third.judgments)
            assertEquals(language, client.checks.first().third.language)
        }
    }

    private data class ExpectedFixes(val all: String, val single: List<String>, val withheld: Boolean = false)

    private val expectedFixes = mapOf(
        "ca-005:auto" to ExpectedFixes(
            "El gos s'ha adormit al sofà. Si truques al timbre, es despertarà.",
            listOf(
                "El gos s'ha adormit alsofà.Si truques al tibmre, es despertarà.",
                "El gos s'ha adorimt al sofà.Si truques al tibmre, es despertarà.",
                "El gos s'ha adorimt alsofà. Si truques al tibmre, es despertarà.",
                "El gos s'ha adorimt alsofà.Si truques al timbre, es despertarà.",
            ),
        ),
        "ca-035:auto" to ExpectedFixes(
            "La pel·lícula comença a dos quarts de nou. Millor que hi arribem una mica abans.",
            listOf(
                "La pel·lícula comença a dos quarts de nou. Mi llor que hi arrbem una micaabans.",
                "La pel·lícula comença a dos quarst de nou. Millor que hi arrbem una micaabans.",
                "La pel·lícula comença a dos quarst de nou. Mi llor que hi arribem una micaabans.",
                "La pel·lícula comença a dos quarst de nou. Mi llor que hi arrbem una mica abans.",
            ),
        ),
        "sp-016:Spanish" to ExpectedFixes(
            "Me he equivocado de calle. Ahora estoy delante de la biblioteca. Envíame la ubicación, por favor.",
            listOf(
                "Me he equivocado de calle. Ahora estoy delante de la biblioteca. Envíame la ubicación, por f¿avor.",
                "Me he equivocado de calle. Ahora estoy delante dela biblioteca. Envíame la ubicación, por favor.",
            ),
        ),
        "sp-036:Spanish" to ExpectedFixes(
            "He recibido tu correo. Revisaré los documentos después de comer. Te responderé antes de las seis.",
            listOf(
                "He recibido tu correo. Revisarelos docum:entos despés de comer. Te resp onderé antes de las seis.",
                "He recib ido tu correo. Revisaré los docum:entos despés de comer. Te resp onderé antes de las seis.",
                "He recib ido tu correo. Revisarelos documentos despés de comer. Te resp onderé antes de las seis.",
                "He recib ido tu correo. Revisarelos docum:entos después de comer. Te resp onderé antes de las seis.",
                "He recib ido tu correo. Revisarelos docum:entos despés de comer. Te responderé antes de las seis.",
            ),
        ),
        "sp-050:auto" to ExpectedFixes(
            "Todavía no he decidido qué voy a hacer. Necesito pensarlo un poco más. Te diré algo mañana.",
            listOf(
                "Todavía no he decidido qué voy a hace\r\nr. Necesito pensarlo un poco mas. Te dir é algo mañana.",
                "Todavía no he decidido quévoy a hacer. Necesito pensarlo un poco mas. Te dir é algo mañana.",
                "Todavía no he decidido quévoy a hace\r\nr. Necesito pensarlo un poco más. Te dir é algo mañana.",
                "Todavía no he decidido quévoy a hace\r\nr. Necesito pensarlo un poco mas. Te diré algo mañana.",
            ),
        ),
        "sp-012:auto" to ExpectedFixes(
            "El vuelo llega a las 2130. Julia nos esperará a la salida. La avisaré si hay algún retraso.",
            listOf(
                "El vuelo llegga a las 2130. Julia nos esperará a la salwida. La avisaré pero hay abuelo retraso.",
                "El uelo llega a las 2130. Julia nos esperará a la salwida. La avisaré pero hay abuelo retraso.",
                "El uelo llegga a las 2130. Julia nos esperará a la salida. La avisaré pero hay abuelo retraso.",
                "El uelo llegga a las 2130. Julia nos esperará a la salwida. La avisaré si hay abuelo retraso.",
                "El uelo llegga a las 2130. Julia nos esperará a la salwida. La avisaré pero hay algún retraso.",
            ),
        ),
        // aL -> ja que la combines an inferred connector with an article repair.
        // Keep the four independent word repairs; do not offer that opaque expansion.
        "ca-024:auto" to ExpectedFixes(
            "He de prendre aquesta pastilla després de sopar aL metgessa m'ha dit que no en prengui dues. Ho tinc apuntat al calendari.",
            listOf(
                "He de prendre aquesta pastilla despres de sopar aL metgessa m'ha veure que no en prengui dues. Ho ti nc apuntat al calendari.",
                "He de prendre aquesta pasti:lla després de sopar aL metgessa m'ha veure que no en prengui dues. Ho ti nc apuntat al calendari.",
                "He de prendre aquesta pasti:lla despres de sopar aL metgessa m'ha dit que no en prengui dues. Ho ti nc apuntat al calendari.",
                "He de prendre aquesta pasti:lla despres de sopar aL metgessa m'ha veure que no en prengui dues. Ho tinc apuntat al calendari.",
            ),
            withheld = true,
        ),
    )
}
