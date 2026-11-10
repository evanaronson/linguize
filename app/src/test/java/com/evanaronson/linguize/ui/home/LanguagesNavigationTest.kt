package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.ui.components.TopBar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lab, lang-selector: the selector and Settings open the same Languages page; Settings › About opens the notices. */
class LanguagesNavigationTest {
    @Test fun theSelectorAndSettingsOpenTheSamePage() {
        val fromSelector = Navigation(Screen.Chat).open(Screen.Languages)
        val fromSettings = Navigation(Screen.Chat).open(Screen.Settings).open(Screen.Languages)
        assertEquals(Screen.Languages, fromSelector.current)
        assertEquals(fromSelector.current, fromSettings.current)
        assertEquals(Screen.Chat, fromSelector.back().current)
        assertEquals(Screen.Settings, fromSettings.back().current)
        assertEquals(Screen.Languages, Navigation.restore(fromSettings.saved).current)
        assertEquals(listOf("lab_chat", "settings", "lab_lang_selector"), fromSettings.saved)
        assertEquals(TopBar.Page("Languages"), Screen.Languages.topBar("Languages"))
    }

    @Test fun noticesOpenFromSettingsAndSurviveRestore() {
        val navigation = Navigation(Screen.Notes).open(Screen.Settings).open(Screen.Notices)
        assertEquals(listOf("notes", "settings", "lab_lang_selector_notices"), navigation.saved)
        assertEquals(Screen.Notices, Navigation.restore(navigation.saved).current)
        assertEquals(TopBar.Page("Open-source notices"), Screen.Notices.topBar("Open-source notices"))
    }
}
