package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the app starts ("Open to chat"), and how the other tabs and Settings sit around it. */
class ChatStartNavigationTest {
    @Test fun openToChatStartsInTheChatWithNothingBehindIt() {
        val start = startNavigation(openToChat = true, openSettings = false)
        assertEquals(Screen.Chat, start.current)
        assertNull(start.parent)
    }

    @Test fun switchedOffTheAppStartsOnWrite() {
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false))
        assertEquals(Screen.Write, Navigation().current)
        assertEquals(Navigation().open(Screen.Settings), startNavigation(openToChat = false, openSettings = true))
    }

    @Test fun writeOpensAboveTheChatAndBackReturnsToIt() {
        val write = startNavigation(openToChat = true, openSettings = false).open(Screen.Write)
        assertEquals(Screen.Write, write.current)
        assertEquals(Screen.Chat, write.parent)
        assertEquals(Screen.Chat, write.back().current)
        // Opening Write again from deep inside another tab goes back to Write, still above the chat.
        val deep = write.open(Screen.History).open(Screen.Detail("a"))
        assertEquals(write, deep.open(Screen.Write))
    }

    @Test fun settingsIsOneTapFromTheChatAndReturnsToIt() {
        val settings = startNavigation(openToChat = true, openSettings = false).open(Screen.Settings)
        assertEquals(Screen.Chat, settings.parent)
        assertEquals(startNavigation(openToChat = true, openSettings = true), settings)
    }

    @Test fun theChatPopsBackToItWhenItIsTheStart() {
        val deep = startNavigation(openToChat = true, openSettings = false).open(Screen.Progress).open(Screen.Phrasebook)
        assertEquals(Navigation(Screen.Chat), deep.open(Screen.Chat))
    }

    @Test fun theChatWhenWriteIsTheStartOpensAboveIt() {
        val chat = Navigation().open(Screen.Chat)
        assertEquals(Screen.Write, chat.parent)
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

    @Test fun theTryItShortcutOpensWriteAboveEitherStart() {
        val fromChat = startNavigation(openToChat = true, openSettings = false, tab = Screen.Write)
        assertEquals(listOf("lab_chat", "home"), fromChat.saved)
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false, tab = Screen.Write))
        // Only tabs can be asked for.
        assertEquals(Navigation(Screen.Chat), startNavigation(openToChat = true, openSettings = false, tab = Screen.Practice))
    }
}
