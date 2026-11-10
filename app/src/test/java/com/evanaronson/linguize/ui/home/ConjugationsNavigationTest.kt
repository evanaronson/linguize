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
    fun showAllOpensTheVerbListAndAVerbOpensWords() {
        val cell = Cell(Tense.Present, Person.S1)
        val all = Navigation().open(Screen.Progress).open(Screen.Conjugations).open(conjugationVerbsScreen("es", null))
        assertEquals(Screen.ConjugationVerbs("es", null), all.current)
        assertEquals("conjugation_verbs:es:", all.current.saved)
        assertEquals(all, Navigation.restore(all.saved))

        val fromCell = Navigation().open(Screen.Conjugations).open(Screen.ConjugationCell("ca", cell.token))
            .open(conjugationVerbsScreen("ca", cell)).open(conjugationVerbWordScreen("ca", "parlar"))
        assertEquals(Screen.WordDetail("ca", "parlar/v", "x"), fromCell.current)
        assertEquals(Screen.ConjugationVerbs("ca", "present:1s"), fromCell.back().current)
        assertEquals(fromCell, Navigation.restore(fromCell.saved))
        assertNull(Screen.restore("conjugation_verbs:"))
        assertNull(Screen.restore("conjugation_verbs:es"))
    }

    @Test
    fun malformedCellsAreNotRestored() {
        assertNull(Screen.restore("conjugation:"))
        assertNull(Screen.restore("conjugation:es"))
        assertEquals(Screen.ConjugationCell("es", "gerund"), Screen.restore("conjugation:es:gerund"))
    }
}
