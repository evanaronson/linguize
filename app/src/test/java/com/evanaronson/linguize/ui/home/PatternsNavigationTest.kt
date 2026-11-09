package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternsNavigationTest {
    @Test
    fun patternScreensSurviveSavedState() {
        val path = Navigation().open(Screen.Progress).open(Screen.Patterns).open(Screen.PatternDetail("accents")).open(Screen.Detail("s1"))
        assertEquals(listOf("notes", "progress", "patterns", "pattern:accents", "detail:s1"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Screen.PatternDetail("accents"), path.back().current)
    }

    @Test
    fun emptyPatternTokenIsNotRestored() {
        assertNull(Screen.restore("pattern:"))
        assertEquals(Screen.Patterns, Screen.restore("patterns"))
    }
}
