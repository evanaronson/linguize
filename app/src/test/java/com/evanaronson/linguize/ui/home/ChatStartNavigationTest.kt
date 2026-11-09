package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the app starts ("Open to chat"), and how the other tabs and Settings sit around it. */
class ChatStartNavigationTest {
    @Test fun openToChatStartsInTheChatWithNothingBehindIt() {
        val start = startNavigation(openToChat = true, openSettings = false)
        // Into the current conversation, above the Chat tab's list, which is the start.
        assertEquals(Screen.ChatThread(null), start.current)
        assertEquals(Screen.Chat, start.parent)
        assertNull(start.back().parent)
    }

    @Test fun switchedOffTheAppStartsOnNotes() {
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false))
        assertEquals(Screen.Notes, Navigation().current)
        assertEquals(Navigation().open(Screen.Settings), startNavigation(openToChat = false, openSettings = true))
    }

    @Test fun notesOpensAboveTheChatAndBackReturnsToIt() {
        val write = startNavigation(openToChat = true, openSettings = false).open(Screen.Notes)
        assertEquals(Screen.Notes, write.current)
        assertEquals(Screen.Chat, write.parent)
        assertEquals(Screen.Chat, write.back().current)
        // Opening Notes again from deep inside another tab goes back to Notes, still above the chat.
        val deep = write.open(Screen.History).open(Screen.Detail("a"))
        assertEquals(write, deep.open(Screen.Notes))
    }

    @Test fun settingsIsOneTapFromTheChatAndReturnsToIt() {
        val settings = Navigation(Screen.Chat).open(Screen.Settings)
        assertEquals(Screen.Chat, settings.parent)
        assertEquals(startNavigation(openToChat = true, openSettings = true), settings)
        // From a conversation, Settings returns to it.
        val fromThread = startNavigation(openToChat = true, openSettings = false).open(Screen.Settings)
        assertEquals(Screen.ChatThread(null), fromThread.parent)
    }

    @Test fun theChatPopsBackToItWhenItIsTheStart() {
        val deep = startNavigation(openToChat = true, openSettings = false).open(Screen.Progress).open(Screen.Phrasebook)
        assertEquals(Navigation(Screen.Chat), deep.open(Screen.Chat))
    }

    @Test fun theChatWhenNotesIsTheStartOpensAboveIt() {
        val chat = Navigation().open(Screen.Chat)
        assertEquals(Screen.Notes, chat.parent)
        assertEquals(Navigation(), chat.back())
    }

    @Test fun restoringKeepsTheChatAsTheStart() {
        val path = startNavigation(openToChat = true, openSettings = false).open(Screen.History).open(Screen.Detail("s1"))
        assertEquals(listOf("lab_chat", "history", "detail:s1"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Navigation(), Navigation.restore(listOf("home")))
        // An old path (Home above the chat, then History) restores as History above the chat.
        assertEquals(Navigation(Screen.Chat).open(Screen.History), Navigation.restore(listOf("lab_chat", "home", "history")))
    }

    @Test fun theNotesShortcutOpensNotesAboveEitherStart() {
        val fromChat = startNavigation(openToChat = true, openSettings = false, tab = Screen.Notes)
        assertEquals(listOf("lab_chat", "notes"), fromChat.saved)
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false, tab = Screen.Notes))
        // Only tabs can be asked for.
        assertEquals(startNavigation(openToChat = true, openSettings = false),
            startNavigation(openToChat = true, openSettings = false, tab = Screen.Practice))
    }
}
