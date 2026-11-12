package com.evanaronson.linguize.core.conjugations

import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class VerbListTest {
    private val present1 = Cell(Tense.Present, Person.S1)
    private val preterite3 = Cell(Tense.Preterite, Person.S3)
    private val gerund = Cell(Tense.Gerund, null)

    private fun verb(lemma: String, weight: Double, cells: Set<Cell>, last: Long = 0) =
        VerbStats(lemma, weight, cells.mapTo(linkedSetOf()) { it.tense }, cells, last)

    private fun cellStats(cell: Cell, verbs: Map<String, Double>) = CellStats(cell, verbs.values.sum(), 0, verbs, 0, emptyList())

    /** Hundreds of verbs: "verbo000" used once … "verbo299" used 300 times. */
    private val many: ConjugationReport = run {
        val verbs = (0 until 300).map { verb("verbo%03d".format(it), it + 1.0, setOf(present1), last = it.toLong()) }.reversed()
        ConjugationReport("es", mapOf(present1 to cellStats(present1, verbs.associate { it.lemma to it.weight })), verbs, emptyList(), emptyList(), 1)
    }

    private val small = ConjugationReport(
        language = "es",
        cells = mapOf(
            present1 to cellStats(present1, mapOf("hablar" to 3.0, "comer" to 1.0)),
            preterite3 to cellStats(preterite3, mapOf("hablar" to 1.0, "ir" to 0.5)),
            gerund to cellStats(gerund, mapOf("comer" to 2.0)),
        ),
        verbs = listOf(
            verb("hablar", 4.0, setOf(present1, preterite3)),
            verb("comer", 3.0, setOf(present1, gerund)),
            verb("ir", 0.5, setOf(preterite3)),
        ),
        tried = emptyList(),
        trend = emptyList(),
        utterances = 3,
    )

    @Test
    fun aPreviewIsCappedAndCountsEveryVerb() {
        val preview = VerbList.preview(VerbList.rows(many))
        assertEquals(VerbList.PREVIEW, preview.shown.size)
        assertEquals(300, preview.total)
        assertTrue(preview.more)
        // Most used first.
        assertEquals(listOf("verbo299", "verbo298", "verbo297", "verbo296", "verbo295"), preview.shown.map { it.lemma })

        val few = VerbList.preview(VerbList.rows(small))
        assertEquals(3, few.total)
        assertFalse(few.more)
    }

    @Test
    fun rowsCarryFormsAndTensesInGridOrder() {
        val rows = VerbList.rows(small)
        assertEquals(listOf("hablar", "comer", "ir"), rows.map { it.lemma })
        val comer = rows.first { it.lemma == "comer" }
        assertEquals(2, comer.cells)
        // Finite rows first, then the non-finite forms.
        assertEquals(listOf(Tense.Present, Tense.Gerund), comer.tenses)
        assertEquals(listOf(Tense.Present, Tense.Preterite), rows.first().tenses)
    }

    @Test
    fun aCellsRowsAreItsVerbsWithTheirUsesThere() {
        val rows = VerbList.rows(small, preterite3)
        assertEquals(listOf("hablar" to 1.0, "ir" to 0.5), rows.map { it.lemma to it.weight })
        assertEquals(emptyList<VerbRow>(), VerbList.rows(small, Cell(Tense.Future, Person.P1)))
        assertEquals(listOf(VerbSort.MostUsed, VerbSort.Alphabetical), VerbList.sorts(preterite3))
        assertEquals(listOf(VerbSort.MostUsed, VerbSort.MostForms, VerbSort.Alphabetical), VerbList.sorts(null))
    }

    @Test
    fun searchIsAccentInsensitiveAndSortsOrder() {
        val rows = VerbList.rows(small) + VerbRow("oír", 0.2, 1, listOf(Tense.Present), 0)
        assertEquals(listOf("oír"), VerbList.arrange(rows, VerbSort.MostUsed, "oir").map { it.lemma })
        assertEquals(listOf("hablar"), VerbList.arrange(rows, VerbSort.MostUsed, " HAB ").map { it.lemma })
        assertEquals(listOf("comer", "hablar", "ir", "oír"), VerbList.arrange(rows, VerbSort.Alphabetical, locale = Locale.forLanguageTag("es")).map { it.lemma })
        // Two forms each: hablar has more uses.
        assertEquals(listOf("hablar", "comer", "ir", "oír"), VerbList.arrange(rows, VerbSort.MostForms).map { it.lemma })
        assertEquals(300, VerbList.arrange(VerbList.rows(many), VerbSort.MostUsed, "verbo").size)
        assertEquals(VerbSort.MostForms, VerbSort.read("forms"))
    }

    @Test
    fun aBuiltReportsVerbsMatchItsCells() {
        val report = ConjugationMap.build(
            listOf(Utterance("check:1", "check", "es", "Hablo mucho. Hablé ayer. Comiendo.", at = 1)),
            "es", com.evanaronson.linguize.core.morphology.SharedMorphology.instance, ZoneOffset.UTC,
        )
        val hablar = VerbList.rows(report).first { it.lemma == "hablar" }
        assertEquals(2, hablar.cells)
        assertEquals(listOf("hablar"), VerbList.rows(report, present1).map { it.lemma })
    }
}
