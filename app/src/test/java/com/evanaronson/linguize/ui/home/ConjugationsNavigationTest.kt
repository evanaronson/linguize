package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConjugationsNavigationTest {
    @Test
    fun conjugationScreensSurviveSavedState() {
        val token = Cell(Tense.PresentSubjunctive, Person.P1).token
        val path = Navigation().open(Screen.Progress).open(Screen.Conjugations).open(Screen.ConjugationCell("ca", token))
        assertEquals(listOf("notes", "progress", "conjugations", "conjugation:ca:present_subjunctive:1p"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Screen.Conjugations, path.back().current)
    }

    @Test
    fun malformedCellsAreNotRestored() {
        assertNull(Screen.restore("conjugation:"))
        assertNull(Screen.restore("conjugation:es"))
        assertEquals(Screen.ConjugationCell("es", "gerund"), Screen.restore("conjugation:es:gerund"))
    }
}
