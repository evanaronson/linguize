package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fill the gaps became part of every check; a saved path that was on its screen lands on Write. */
class GapNavigationTest {
    @Test fun theOldFillTheGapsScreenRestoresToWrite() {
        assertEquals(Screen.Write, Screen.restore("lab_gap_filler"))
        assertEquals(Navigation(), Navigation.restore(listOf("home", "lab_gap_filler")))
        assertEquals(Navigation(Screen.Chat).open(Screen.Write), Navigation.restore(listOf("lab_chat", "home", "lab_gap_filler")))
    }
}
