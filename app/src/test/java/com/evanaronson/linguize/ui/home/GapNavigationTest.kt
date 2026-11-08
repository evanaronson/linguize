package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GapNavigationTest {
    @Test fun fillTheGapsIsRestoredAboveHome() {
        val navigation = Navigation().open(Screen.FillGaps)
        assertEquals(listOf("home", "lab_gap_filler"), navigation.saved)
        assertEquals(Screen.FillGaps, Navigation.restore(navigation.saved).current)
        assertEquals(Screen.Home, navigation.back().current)
    }
}
