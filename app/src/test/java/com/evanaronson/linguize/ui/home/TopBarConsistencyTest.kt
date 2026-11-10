package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.ui.components.TopBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lab, Consistency: one top bar on the four tabs (the wordmark, the language selector, then
 * Settings), Back and a title everywhere else, and one language selector in the app. The
 * popups over other apps keep the card's own picker.
 */
class TopBarConsistencyTest {
    private val source = File("src/main/java/com/evanaronson/linguize")

    private fun read(path: String) = File(source, path).readText()

    private fun kotlinIn(dir: String) = File(source, dir).walk().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun everyTabHasTheSameRootBar() {
        assertEquals(listOf(Screen.Chat, Screen.Notes, Screen.Progress, Screen.History), Screen.tabs)
        Screen.tabs.forEach { assertEquals(TopBar.Root, it.topBar("ignored")) }
    }

    @Test fun everyOtherPageHasBackAndItsTitle() {
        val pages = listOf(
            Screen.Settings, Screen.AccessibilitySetup, Screen.Detail("s1"),
            Screen.Phrasebook, Screen.AppVoice(), Screen.Patterns, Screen.Practice, Screen.Memory, Screen.Words,
            Screen.Conjugations, Screen.TimeSpent, Screen.ChatThread("c1"),
        )
        pages.forEach { assertEquals(TopBar.Page("Title"), it.topBar("Title")) }
        assertEquals(TopBar.Page("Weekend plans", "Talking to Marta"), Screen.ChatThread("c1").topBar("Weekend plans", "Talking to Marta"))
    }

    @Test fun theBarDrawsTheSelectorAndSettingsOnlyOnARoot() {
        val bar = read("ui/components/AppNavigation.kt")
        assertTrue(bar.contains("if (bar == TopBar.Root) {\n                languageSelector()\n                IconButton(onClick = onOpenSettings)"))
        // The activity and a conversation draw the same bar; nothing else builds a top bar of its own.
        val bars = kotlinIn("ui").filter { "TopAppBar(" in it.readText() }.map { it.name }
        assertEquals(listOf("AppNavigation.kt"), bars)
        assertTrue("AppNavigation(" in read("ui/chat/ChatChrome.kt"))
    }

    @Test fun thereIsOneLanguageSelectorInTheApp() {
        val call = Regex("""\bLanguageSwitch\(""")
        val selectors = kotlinIn("").filter { call.containsMatchIn(it.readText()) && it.name != "LanguageSwitch.kt" }.map { it.name }
        assertEquals(listOf("HomeActivity.kt"), selectors)
        // No per-screen language choice in Notes, the chat (new-conversation sheet included), or History.
        (kotlinIn("ui/notes") + kotlinIn("ui/chat") + kotlinIn("ui/home") + kotlinIn("ui/history") + kotlinIn("ui/progress")).forEach {
            assertFalse(it.name, "LanguagePicker" in it.readText())
        }
        assertFalse("FilterDropdown" in read("ui/chat/ChatChrome.kt"))
        assertFalse("lab_language_scope_all" in read("ui/history/HistoryScreen.kt"))
    }

    @Test fun thePopupsOverOtherAppsKeepTheCardsPicker() {
        listOf(
            "menu/CheckActivity.kt", "menu/ShareActivity.kt", "menu/ClipboardActivity.kt",
            "accessibility/LinguizeAccessibilityService.kt",
        ).forEach { assertTrue(it, "LanguagePicker(" in read(it)) }
    }
}
