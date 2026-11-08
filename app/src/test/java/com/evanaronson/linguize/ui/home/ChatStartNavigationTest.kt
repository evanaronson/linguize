package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab Chat: where the app starts, and how Home and Settings sit around the chat. */
class ChatStartNavigationTest {
    @Test fun openToChatStartsInTheChatWithNothingBehindIt() {
        val start = startNavigation(openToChat = true, openSettings = false)
        assertEquals(Screen.Chat, start.current)
        assertNull(start.parent)
    }

    @Test fun switchedOffTheAppStartsOnHomeAsBefore() {
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false))
        assertEquals(Navigation().open(Screen.Settings), startNavigation(openToChat = false, openSettings = true))
    }

    @Test fun homeOpensAboveTheChatAndBackReturnsToIt() {
        val home = startNavigation(openToChat = true, openSettings = false).open(Screen.Home)
        assertEquals(Screen.Home, home.current)
        assertEquals(Screen.Chat, home.parent)
        assertEquals(Screen.Chat, home.back().current)
        // Opening Home again from deep inside goes back to Home, still above the chat.
        val deep = home.open(Screen.History).open(Screen.Detail("a"))
        assertEquals(home, deep.open(Screen.Home))
    }

    @Test fun settingsIsOneTapFromTheChatAndReturnsToIt() {
        val settings = startNavigation(openToChat = true, openSettings = false).open(Screen.Settings)
        assertEquals(Screen.Chat, settings.parent)
        assertEquals(startNavigation(openToChat = true, openSettings = true), settings)
    }

    @Test fun theChatFromHomePopsBackToItWhenItIsTheRoot() {
        val deep = startNavigation(openToChat = true, openSettings = false).open(Screen.Home).open(Screen.Phrasebook)
        assertEquals(Navigation(Screen.Chat), deep.open(Screen.Chat))
    }

    @Test fun theChatFromHomeWhenHomeIsTheRootOpensAbove() {
        val chat = Navigation().open(Screen.Chat)
        assertEquals(Screen.Home, chat.parent)
        assertEquals(Navigation(), chat.back())
    }

    @Test fun restoringKeepsTheChatAsTheRoot() {
        val path = startNavigation(openToChat = true, openSettings = false).open(Screen.Home).open(Screen.History)
        assertEquals(listOf("lab_chat", "home", "history"), path.saved)
        assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Navigation(), Navigation.restore(listOf("home")))
    }
}
