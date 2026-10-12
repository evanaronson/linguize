package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    @Test
    fun aCheckOpenedFromRecentReturnsDirectlyHome() {
        val navigation = Navigation().open(Screen.Detail("recent"))
        assertEquals(Screen.Home, navigation.parent)
        assertEquals(Screen.Home, navigation.back().current)
        assertNull(navigation.back().parent)
    }

    @Test
    fun aCheckOpenedFromHistoryReturnsThroughAllThreeLevels() {
        val navigation = Navigation().open(Screen.History).open(Screen.Detail("older"))
        assertEquals(Screen.History, navigation.parent)
        assertEquals(Screen.History, navigation.back().current)
        assertEquals(Screen.Home, navigation.back().back().current)
        assertEquals(Navigation(), navigation.back().back().back())
    }

    @Test
    fun restoringADeepPageKeepsItsParentAndSettingsReturnsToItsEntryPoint() {
        val original = Navigation().open(Screen.History).open(Screen.Detail("older"))
        assertEquals(original, Navigation.restore(original.saved))
        val settings = original.back().open(Screen.Settings)
        assertEquals(Screen.History, Navigation.restore(settings.saved).back().current)
        assertEquals(Screen.Home, Navigation().open(Screen.Settings).back().current)
    }

    @Test
    fun legacyStateAndInvalidStateHaveASafeRoot() {
        assertEquals(Screen.Home, Navigation.restore(listOf("detail:legacy")).parent)
        assertEquals(Navigation(), Navigation.restore(listOf("unknown", "detail:")))
        assertEquals(Navigation(), Navigation.restore(null))
    }
}
