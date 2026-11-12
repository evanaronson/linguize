package com.evanaronson.linguize.ui.settings

import com.evanaronson.linguize.ui.home.Navigation
import com.evanaronson.linguize.ui.home.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Second opinion was removed: no code, string or doc mentions it, and a page saved while it
 * was in Settings restores to Settings.
 */
class RemovedSettingsTest {
    @Test fun aSavedSecondOpinionPageRestoresToSettings() {
        assertNull(SettingsPage.read("second_opinion"))
        assertEquals(Navigation().open(Screen.Settings), Navigation.restore(listOf("notes", "settings", "settings:second_opinion")))
        assertEquals(Navigation(Screen.Chat).open(Screen.Settings),
            Navigation.restore(listOf("lab_chat", "settings", "settings:second_opinion")))
    }

    @Test fun nothingInTheAppOrItsDocsMentionsIt() {
        val mention = Regex("second[ _-]?opinion|core\\.opinion", RegexOption.IGNORE_CASE)
        val roots = listOf(File("src/main/java"), File("src/main/res"), File("../docs"), File("../README.md"))
        assertTrue(roots.all { it.exists() })
        val found = roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { it.extension in setOf("kt", "xml", "md") && mention.containsMatchIn(it.readText()) }
        assertEquals(emptyList<File>(), found)
    }
}
