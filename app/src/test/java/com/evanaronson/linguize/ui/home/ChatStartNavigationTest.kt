package com.evanaronson.linguize.ui.home

import com.evanaronson.linguize.data.chat.StartTab
import com.evanaronson.linguize.ui.settings.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the app starts ("Start on"), and how the other tabs and Settings sit around it. */
class ChatStartNavigationTest {
    @Test fun chatStartsInTheCurrentConversationWithNothingBehindTheList() {
        val start = startNavigation(StartTab.Chat, openSettings = false)
        // Into the current conversation, above the Chat tab's list, which is the start.
        assertEquals(Screen.ChatThread(null), start.current)
        assertEquals(Screen.Chat, start.parent)
        assertNull(start.back().parent)
    }

    @Test fun notesStartsInTheCurrentNoteWithNothingBehindTheList() {
        val start = startNavigation(StartTab.Notes, openSettings = false)
        assertEquals(Screen.Note(null), start.current)
        assertEquals(Screen.Notes, start.parent)
        assertEquals(Screen.Notes, start.tab)
        assertEquals(Navigation(), start.back())
    }

    @Test fun progressAndHistoryStartOnTheirTabAlone() {
        assertEquals(Navigation(Screen.Progress), startNavigation(StartTab.Progress, openSettings = false))
        assertEquals(Navigation(Screen.History), startNavigation(StartTab.History, openSettings = false))
        listOf(StartTab.Progress, StartTab.History).forEach { tab ->
            val start = startNavigation(tab, openSettings = false)
            assertEquals(tab.screen, start.current)
            assertEquals(tab.screen, start.tab)
            assertNull(start.parent)
        }
    }

    @Test fun theChosenTabIsTheRootBackReturnsTo() {
        StartTab.entries.forEach { tab ->
            val root = Navigation(tab.screen)
            Screen.tabs.filter { it != tab.screen }.forEach { other ->
                val path = startNavigation(tab, openSettings = false).open(other).open(Screen.Settings)
                assertEquals(listOf(tab.screen, other, Screen.Settings), path.saved.mapNotNull(Screen::restore))
                assertEquals(other, path.tab)
                assertEquals(root, path.back().back())
                // Opening the start tab from anywhere pops back to it.
                assertEquals(root, path.open(tab.screen))
            }
            // Back from the start leaves the app.
            assertNull(root.parent)
        }
    }

    @Test fun eachStartRestoresWithItsRoot() {
        StartTab.entries.forEach { tab ->
            val path = startNavigation(tab, openSettings = false).open(Screen.Settings)
                .open(Screen.SettingsChoice(SettingsPage.StartOn))
            assertEquals(path, Navigation.restore(path.saved))
            assertEquals(Navigation(tab.screen), Navigation.restore(listOf(tab.screen.saved)))
            val above = Screen.tabs.first { it != tab.screen }
            assertEquals(Navigation(tab.screen).open(above), Navigation.restore(Navigation(tab.screen).open(above).saved))
        }
        assertEquals("settings:start", Screen.SettingsChoice(SettingsPage.StartOn).saved)
    }

    @Test fun theOldSwitchStartsWhereItDid() {
        assertEquals(startNavigation(StartTab.Chat, openSettings = false), startNavigation(openToChat = true, openSettings = false))
        assertEquals(startNavigation(StartTab.Notes, openSettings = true), startNavigation(openToChat = false, openSettings = true))
    }

    @Test fun notesOpensAboveTheChatAndBackReturnsToIt() {
        val write = startNavigation(StartTab.Chat, openSettings = false).open(Screen.Notes)
        assertEquals(Screen.Notes, write.current)
        assertEquals(Screen.Chat, write.parent)
        assertEquals(Screen.Chat, write.back().current)
        // Opening Notes again from deep inside another tab goes back to Notes, still above the chat.
        val deep = write.open(Screen.History).open(Screen.Detail("a"))
        assertEquals(write, deep.open(Screen.Notes))
    }

    @Test fun settingsIsOneTapFromTheStartAndReturnsToIt() {
        val settings = Navigation(Screen.Chat).open(Screen.Settings)
        assertEquals(Screen.Chat, settings.parent)
        assertEquals(startNavigation(StartTab.Chat, openSettings = true), settings)
        // From a conversation, Settings returns to it.
        val fromThread = startNavigation(StartTab.Chat, openSettings = false).open(Screen.Settings)
        assertEquals(Screen.ChatThread(null), fromThread.parent)
        assertEquals(Navigation(Screen.History).open(Screen.Settings), startNavigation(StartTab.History, openSettings = true))
        assertEquals(Navigation().open(Screen.Settings), startNavigation(StartTab.Notes, openSettings = true))
    }

    @Test fun theChatPopsBackToItWhenItIsTheStart() {
        val deep = startNavigation(StartTab.Chat, openSettings = false).open(Screen.Progress).open(Screen.Phrasebook)
        assertEquals(Navigation(Screen.Chat), deep.open(Screen.Chat))
    }

    @Test fun theChatWhenAnotherTabIsTheStartOpensAboveItWithoutAConversation() {
        val chat = Navigation().open(Screen.Chat)
        assertEquals(Screen.Notes, chat.parent)
        assertEquals(Navigation(), chat.back())
        val fromProgress = startNavigation(StartTab.Progress, openSettings = false).open(Screen.Chat)
        assertEquals(listOf("progress", "lab_chat"), fromProgress.saved)
        assertEquals(Navigation(Screen.Progress), fromProgress.back())
    }

    @Test fun restoringKeepsTheChatAsTheStart() {
        val path = startNavigation(StartTab.Chat, openSettings = false).open(Screen.History).open(Screen.Detail("s1"))
        assertEquals(listOf("lab_chat", "history", "detail:s1"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Navigation(), Navigation.restore(listOf("home")))
        // An old path (Home above the chat, then History) restores as History above the chat.
        assertEquals(Navigation(Screen.Chat).open(Screen.History), Navigation.restore(listOf("lab_chat", "home", "history")))
    }

    @Test fun theNotesShortcutOpensTheCurrentNoteAboveAnyStart() {
        val fromChat = startNavigation(StartTab.Chat, openSettings = false, tab = Screen.Notes)
        assertEquals(listOf("lab_chat", "notes", "lab_notes_note"), fromChat.saved)
        assertEquals(startNavigation(StartTab.Notes, openSettings = false), startNavigation(StartTab.Notes, openSettings = false, tab = Screen.Notes))
        assertEquals(listOf("history", "notes", "lab_notes_note"), startNavigation(StartTab.History, openSettings = false, tab = Screen.Notes).saved)
        // Only tabs can be asked for.
        assertEquals(startNavigation(StartTab.Chat, openSettings = false),
            startNavigation(StartTab.Chat, openSettings = false, tab = Screen.Practice))
    }
}
