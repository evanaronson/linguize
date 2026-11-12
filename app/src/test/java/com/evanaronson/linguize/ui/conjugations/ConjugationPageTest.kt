package com.evanaronson.linguize.ui.conjugations

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.CellStats
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.conjugations.Example
import com.evanaronson.linguize.core.conjugations.MonthPoint
import com.evanaronson.linguize.core.conjugations.Reading
import com.evanaronson.linguize.core.conjugations.Tried
import com.evanaronson.linguize.core.conjugations.VerbStats
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.YearMonth

class ConjugationPageTest {
    private val present = Cell(Tense.Present, Person.S1)

    private fun report(trend: Boolean, verbs: Boolean, tried: Boolean) = ConjugationReport(
        language = "es",
        cells = mapOf(present to CellStats(present, 1.0, 1, mapOf("hablar" to 1.0), 0, emptyList())),
        verbs = if (verbs) listOf(VerbStats("hablar", 1.0, setOf(Tense.Present), setOf(present), 0)) else emptyList(),
        tried = if (tried) listOf(Tried("hablo", listOf(Reading("hablar", present)), Example("check:1", "Hablo.", "hablo", 0))) else emptyList(),
        trend = if (trend) listOf(MonthPoint(YearMonth.of(2026, 1), 1, 1)) else emptyList(),
        utterances = 1,
    )

    @Test
    fun theOtherFormsRowIsTheNonFiniteFormsWithoutAPerson() {
        val cells = otherFormCells()
        assertEquals(listOf(Tense.Infinitive, Tense.Gerund, Tense.Participle), cells.map { it.tense })
        assertEquals(ConjugationMap.NON_FINITE, cells.map { it.tense })
        cells.forEach { assertEquals(null, it.person) }
        // Each opens the same cell page as the grid, by its token.
        assertEquals(cells, cells.map { Cell.read(it.token) })
    }

    @Test
    fun otherFormsHaveShortHeadersAndFullNames() {
        val short = ConjugationMap.NON_FINITE.map { it.abbreviationResource() }
        val full = ConjugationMap.NON_FINITE.map { it.labelResource("es") }
        assertEquals(3, short.distinct().size)
        short.zip(full).forEach { (abbreviation, name) -> assertNotEquals(name, abbreviation) }
    }

    @Test
    fun theTrendSitsUnderTheSummaryBeforeTheDetail() {
        assertEquals(
            listOf(ConjugationSection.Summary, ConjugationSection.Trend, ConjugationSection.Grid, ConjugationSection.Verbs, ConjugationSection.Tried),
            conjugationSections(report(trend = true, verbs = true, tried = true)),
        )
        assertEquals(
            listOf(ConjugationSection.Summary, ConjugationSection.Grid),
            conjugationSections(report(trend = false, verbs = false, tried = false)),
        )
    }

    @Test
    fun aVerbOpensItsLexemeInWords() {
        assertEquals("hablar/v", verbWordKey("hablar"))
        assertEquals("x", VERB_WORD_LEVEL.token)
    }
}
