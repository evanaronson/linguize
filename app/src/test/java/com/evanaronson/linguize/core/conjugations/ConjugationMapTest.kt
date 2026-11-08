package com.evanaronson.linguize.core.conjugations

import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Morphology
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class ConjugationMapTest {
    private val morphology = Shared.morphology

    private fun at(year: Int, month: Int, day: Int = 1) = LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun say(text: String, language: String = "es", corrected: List<Span> = emptyList(), at: Long = at(2026, 1), id: String = text) =
        Utterance("check:$id", "check", language, text, corrected, at)

    private fun build(language: String, vararg utterances: Utterance) =
        ConjugationMap.build(utterances.toList(), language, morphology, ZoneOffset.UTC)

    private fun cell(tense: Tense, person: Person?) = Cell(tense, person)

    @Test
    fun countsSimpleTensesByPerson() {
        val report = build("es", say("Ayer hablé con mi madre y vivimos juntos."))
        assertEquals(1.0, report.stats(cell(Tense.Preterite, Person.S1))!!.weight, 1e-9)
        assertEquals(mapOf("hablar" to 1.0), report.stats(cell(Tense.Preterite, Person.S1))!!.verbs)
        // vivimos is present or preterite: half each.
        assertEquals(0.5, report.stats(cell(Tense.Preterite, Person.P1))!!.weight, 1e-9)
        assertEquals(0.5, report.stats(cell(Tense.Present, Person.P1))!!.weight, 1e-9)
        assertEquals(0, report.stats(cell(Tense.Present, Person.P1))!!.sure)
        assertEquals(1, report.stats(cell(Tense.Preterite, Person.S1))!!.sure)
    }

    @Test
    fun ambiguousFormsShareOneUse() {
        val report = build("es", say("Ojalá cante bien."))
        assertEquals(0.5, report.stats(cell(Tense.PresentSubjunctive, Person.S1))!!.weight, 1e-9)
        assertEquals(0.5, report.stats(cell(Tense.PresentSubjunctive, Person.S3))!!.weight, 1e-9)
        val fui = build("es", say("Fui al médico."))
        val stats = fui.stats(cell(Tense.Preterite, Person.S1))!!
        assertEquals(1.0, stats.weight, 1e-9)
        assertEquals(mapOf("ir" to 0.5, "ser" to 0.5), stats.verbs)
        assertEquals(1, stats.sure)
    }

    @Test
    fun correctedSpansAreTriedNotCounted() {
        val text = "Yo sabo que tenemos tiempo."
        val start = text.indexOf("sabo")
        val wrongButReal = "Ayer yo hice la cena y fuimos."
        val hice = wrongButReal.indexOf("hice")
        val report = build(
            "es",
            say(text, corrected = listOf(Span(start, start + 4))),
            say(wrongButReal, corrected = listOf(Span(hice, hice + 4))),
        )
        assertNull(report.stats(cell(Tense.Preterite, Person.S1)))
        assertEquals(1.0, report.stats(cell(Tense.Present, Person.P1))!!.weight, 1e-9)
        val tried = report.tried.map { it.form }
        assertTrue("hice" in tried)
        assertEquals(wrongButReal, report.tried.first { it.form == "hice" }.example.sentence)
    }

    @Test
    fun compoundTensesFromHaberAndParticiple() {
        val report = build("es", say("Ya he terminado el trabajo. Habíamos comido. Lo habrán visto. Ojalá hayas ya llegado."))
        assertEquals(mapOf("terminar" to 1.0), report.stats(cell(Tense.Perfect, Person.S1))!!.verbs)
        assertEquals(mapOf("comer" to 1.0), report.stats(cell(Tense.Pluperfect, Person.P1))!!.verbs)
        assertEquals(mapOf("ver" to 1.0), report.stats(cell(Tense.FuturePerfect, Person.P3))!!.verbs)
        assertEquals(mapOf("llegar" to 1.0), report.stats(cell(Tense.PerfectSubjunctive, Person.S2))!!.verbs)
        // The auxiliary and participle are not counted on their own.
        assertNull(report.stats(cell(Tense.Present, Person.S1)))
        assertNull(report.stats(cell(Tense.Participle, null)))
        assertTrue(report.verbs.none { it.lemma == "haber" })
    }

    @Test
    fun catalanCompoundAndPeriphrasticPast() {
        val report = build(
            "ca",
            say("Ahir vaig anar al cine i l'he vist. Vam menjar molt. Em vaig llevar tard. Ho vas dir-li?", language = "ca"),
        )
        val past = report.stats(cell(Tense.PeriphrasticPast, Person.S1))!!
        assertEquals(mapOf("anar" to 1.0, "llevar" to 1.0), past.verbs)
        assertEquals(mapOf("menjar" to 1.0), report.stats(cell(Tense.PeriphrasticPast, Person.P1))!!.verbs)
        assertEquals(mapOf("dir" to 1.0), report.stats(cell(Tense.PeriphrasticPast, Person.S2))!!.verbs)
        assertEquals(mapOf("veure" to 1.0), report.stats(cell(Tense.Perfect, Person.S1))!!.verbs)
        // vaig is not counted as anar's present when it is the auxiliary.
        assertNull(report.stats(cell(Tense.Present, Person.S1)))
    }

    @Test
    fun catalanVaigAloneIsThePresentOfAnar() {
        val report = build("ca", say("Vaig a casa.", language = "ca"))
        assertEquals(mapOf("anar" to 1.0), report.stats(cell(Tense.Present, Person.S1))!!.verbs)
    }

    @Test
    fun nominalContextAndFunctionWordsAreNotVerbs() {
        val report = build("es", say("El trabajo es como un regalo para mí."))
        assertEquals(mapOf("ser" to 1.0), report.stats(cell(Tense.Present, Person.S3))!!.verbs)
        assertEquals(1, report.verbs.size)
        // Pronouns before a verb are not articles.
        val pronoun = build("es", say("La tengo aquí."))
        assertEquals(mapOf("tener" to 1.0), pronoun.stats(cell(Tense.Present, Person.S1))!!.verbs)
    }

    @Test
    fun encliticImperativesAndNonFiniteForms() {
        val report = build("es", say("Dímelo mañana. Estoy pensando en ir."))
        assertEquals(mapOf("decir" to 1.0), report.stats(cell(Tense.Imperative, Person.S2))!!.verbs)
        assertEquals(1.0, report.stats(cell(Tense.Gerund, null))!!.weight, 1e-9)
        assertEquals(1.0, report.stats(cell(Tense.Infinitive, null))!!.weight, 1e-9)
    }

    @Test
    fun onlyTheRequestedLanguageIsRead() {
        val report = build("es", say("Vaig a casa.", language = "ca"), say("Voy a casa."), say("I go home.", language = "en"))
        assertEquals(1, report.utterances)
        assertEquals(mapOf("ir" to 1.0), report.stats(cell(Tense.Present, Person.S1))!!.verbs)
    }

    @Test
    fun examplesLastUsedAndVerbs() {
        val report = build(
            "es",
            say("Hablo mucho.", at = at(2026, 1, 3)),
            say("Hablo poco. Luego leo.", at = at(2026, 2, 5)),
        )
        val stats = report.stats(cell(Tense.Present, Person.S1))!!
        assertEquals(at(2026, 2, 5), stats.lastUsed)
        assertEquals(listOf("Luego leo.", "Hablo poco.", "Hablo mucho."), stats.examples.map { it.sentence })
        val hablar = report.verbs.first { it.lemma == "hablar" }
        assertEquals(2.0, hablar.weight, 1e-9)
        assertEquals(setOf(Tense.Present), hablar.tenses)
    }

    @Test
    fun trendCountsDistinctCellsPerMonth() {
        val report = build(
            "es",
            say("Hablo.", at = at(2026, 1, 3)),
            say("Hablé. Hablo.", at = at(2026, 3, 5)),
        )
        assertEquals(
            listOf(
                MonthPoint(YearMonth.of(2026, 1), 1, 1),
                MonthPoint(YearMonth.of(2026, 2), 0, 1),
                MonthPoint(YearMonth.of(2026, 3), 2, 2),
            ),
            report.trend,
        )
    }

    @Test
    fun gridShapeAndCellTokens() {
        val rows = ConjugationMap.rows("ca")
        assertTrue(Tense.PeriphrasticPast in rows)
        assertTrue(Tense.PeriphrasticPast !in ConjugationMap.rows("es"))
        val report = build("es")
        assertEquals(14 * 6 - 1, report.gridCells.size)
        assertTrue(report.isEmpty)
        assertEquals(Cell(Tense.PresentSubjunctive, Person.P2), Cell.read(Cell(Tense.PresentSubjunctive, Person.P2).token))
        assertEquals(Cell(Tense.Gerund, null), Cell.read("gerund"))
        assertNull(Cell.read("nonsense"))
    }

    @Test
    fun longSentencesAreCutAroundTheForm() {
        val text = "palabra ".repeat(60) + "hablamos " + "palabra ".repeat(60)
        val start = text.indexOf("hablamos")
        val sentence = ConjugationMap.sentence(text, start, start + 8)
        assertTrue(sentence.contains("hablamos"))
        assertTrue(sentence.length <= 205)
    }

    private object Shared {
        val morphology by lazy { Morphology() }
    }
}
