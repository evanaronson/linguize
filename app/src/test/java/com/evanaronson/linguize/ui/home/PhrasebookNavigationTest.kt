package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PhrasebookNavigationTest {
    @Test fun phrasebookAndTheCheckItOpensSurviveRestore() {
        val navigation = Navigation().open(Screen.Phrasebook).open(Screen.Detail("s1"))
        assertEquals(listOf("home", "phrasebook", "detail:s1"), navigation.saved)
        val restored = Navigation.restore(navigation.saved)
        assertEquals(Screen.Detail("s1"), restored.current)
        assertEquals(Screen.Phrasebook, restored.back().current)
    }
}
