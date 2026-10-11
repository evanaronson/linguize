package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun openingTheAppKeepsThePopupReturnRoute() {
        val home = Navigation().fromCard(existing = false).home()
        assertEquals(Screen.Home, home.screen)
        assertTrue(home.backToCard)
        assertEquals(Navigation.Back.Card, home.back)
        assertNull(home.beforeCard)
    }

    @Test
    fun appSettingsReturnHomeBeforeReturningToThePopup() {
        val settings = Navigation().fromCard(existing = false).home().settings()
        assertEquals(Navigation.Back.Home, settings.back)
        assertEquals(Navigation.Back.Card, settings.home().back)
    }

    @Test
    fun recentDetailsDoNotLoseThePopupRoute() {
        val detail = Navigation().fromCard(existing = false).home().detail("session")
        assertEquals(Navigation.Back.Home, detail.back)
        assertEquals(Navigation.Back.Card, detail.home().back)
    }

    @Test
    fun existingAppContentSurvivesRepeatedDetours() {
        val before = Screen.Detail("earlier")
        val again = Navigation(screen = before).fromCard(existing = true).home().settings().fromCard(existing = true)
        assertEquals(before, again.beforeCard)
    }

    @Test
    fun normalNavigationAndLeavingThePopupHaveNoReturnRoute() {
        assertEquals(Navigation.Back.Exit, Navigation().back)
        assertEquals(Navigation.Back.Home, Navigation().settings().back)
        val left = Navigation().fromCard(existing = false).home().leaveCard()
        assertFalse(left.backToCard)
        assertNull(left.beforeCard)
        assertEquals(Navigation.Back.Exit, left.back)
    }
}
