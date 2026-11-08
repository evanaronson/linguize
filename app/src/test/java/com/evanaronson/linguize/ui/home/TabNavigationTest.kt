package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four top-level destinations in the bottom bar, and how Back and restoring treat them. */
class TabNavigationTest {
    private val starts = listOf(Navigation(Screen.Chat), Navigation())

    @Test fun theBottomBarHasFourDestinationsInOrder() {
        assertEquals(listOf(Screen.Chat, Screen.Write, Screen.Progress, Screen.History), Screen.tabs)
        assertTrue(Screen.tabs.all { it.isTab })
        assertFalse(Screen.Settings.isTab)
        assertFalse(Screen.Practice.isTab)
        assertFalse(Screen.Detail("a").isTab)
    }

    @Test fun everyTabSitsRightAboveTheStartAndBackReturnsThere() {
        for (start in starts) {
            for (tab in Screen.tabs) {
                val opened = start.open(tab)
                assertEquals(tab, opened.current)
                assertEquals(tab, opened.tab)
                if (tab == start.current) {
                    assertEquals(start, opened)
                    assertNull(opened.parent)
                } else {
                    assertEquals(start.current, opened.parent)
                    assertEquals(start, opened.back())
                }
            }
        }
    }

    @Test fun switchingTabsReplacesThePathInsteadOfStackingIt() {
        val deep = Navigation(Screen.Chat).open(Screen.Progress).open(Screen.Words).open(Screen.WordDetail("ca", "casa", "f"))
        val history = deep.open(Screen.History)
        assertEquals(listOf("lab_chat", "history"), history.saved)
        assertEquals(Navigation(Screen.Chat), history.back())
        // The same tab again from inside it returns to its top.
        assertEquals(Navigation(Screen.Chat).open(Screen.Progress), deep.open(Screen.Progress))
    }

    @Test fun theSelectedTabFollowsTheScreensOpenedFromIt() {
        val path = Navigation().open(Screen.Progress).open(Screen.Patterns).open(Screen.PatternDetail("accents"))
        assertEquals(Screen.Progress, path.tab)
        assertEquals(Screen.Write, Navigation().open(Screen.Settings).tab)
        assertEquals(Screen.Chat, Navigation(Screen.Chat).open(Screen.Settings).tab)
    }

    @Test fun progressAndItsViewsRestore() {
        for (start in starts) {
            listOf(Screen.Practice, Screen.Words, Screen.Conjugations, Screen.Patterns, Screen.Phrasebook).forEach { view ->
                val path = start.open(Screen.Progress).open(view)
                assertEquals(path, Navigation.restore(path.saved))
                assertEquals(Screen.Progress, path.back().current)
                assertEquals(start, path.back().back())
            }
        }
        assertEquals("progress", Screen.Progress.saved)
        assertEquals(Screen.Progress, Screen.restore("progress"))
    }

    @Test fun writeToolsAndSettingsPagesRestore() {
        listOf(Screen.FillGaps, Screen.LabReply, Screen.Photo).forEach { tool ->
            val path = Navigation(Screen.Chat).open(Screen.Write).open(tool)
            assertEquals(path, Navigation.restore(path.saved))
            assertEquals(Screen.Write, path.back().current)
        }
        val setup = Navigation(Screen.Chat).open(Screen.Settings).open(Screen.AccessibilitySetup)
        assertEquals(setup, Navigation.restore(setup.saved))
        val memory = Navigation().open(Screen.Settings).open(Screen.Memory)
        assertEquals(memory, Navigation.restore(memory.saved))
        val choice = Navigation().open(Screen.Settings).open(Screen.SettingsChoice(SettingsPage.Menu))
        assertEquals(Screen.Settings, Navigation.restore(choice.saved).back().current)
    }
}
