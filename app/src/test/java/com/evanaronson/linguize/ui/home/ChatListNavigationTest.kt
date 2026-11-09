package com.evanaronson.linguize.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The conversation list is the Chat tab's own top level; a conversation opens above it, so
 * Back from a conversation returns to the list and Back from the list follows the tab rules.
 */
class ChatListNavigationTest {
    @Test fun theAppOpensIntoTheCurrentConversationWithTheListOneBackAway() {
        val start = startNavigation(openToChat = true, openSettings = false)
        assertEquals(Screen.ChatThread(null), start.current)
        assertEquals(Screen.Chat, start.parent)
        assertEquals(Screen.Chat, start.tab)
        assertEquals(Navigation(Screen.Chat), start.back())
        assertNull(start.back().parent)
    }

    @Test fun theCurrentConversationIsReplacedByItsIdOnceKnownOrClosedWhenThereIsNone() {
        val start = startNavigation(openToChat = true, openSettings = false)
        val found = start.replace(Screen.ChatThread("c1"))
        assertEquals(listOf("lab_chat", "lab_chat_thread:c1"), found.saved)
        assertEquals(Navigation(Screen.Chat), found.back())
    }

    @Test fun listAndConversationRestore() {
        val thread = Navigation(Screen.Chat).open(Screen.ChatThread("c1"))
        assertEquals(thread, Navigation.restore(thread.saved))
        assertEquals(Screen.ChatThread(null), Screen.restore("lab_chat_thread"))
        assertNull(Screen.restore("lab_chat_thread:"))
        assertEquals(Navigation(Screen.Chat), Navigation.restore(listOf("lab_chat")))
        // With Write as the start, the chat's list and a conversation sit above it.
        val aboveWrite = Navigation().open(Screen.Chat).open(Screen.ChatThread("c2"))
        assertEquals(listOf("home", "lab_chat", "lab_chat_thread:c2"), aboveWrite.saved)
        assertEquals(aboveWrite, Navigation.restore(aboveWrite.saved))
        assertEquals(Navigation(), aboveWrite.back().back())
        assertFalse(Screen.ChatThread("c2").isTab)
    }

    @Test fun anotherConversationReplacesTheOpenOne() {
        val one = Navigation(Screen.Chat).open(Screen.ChatThread("a"))
        val two = one.open(Screen.ChatThread("b"))
        assertEquals(listOf("lab_chat", "lab_chat_thread:b"), two.saved)
        assertEquals(Navigation(Screen.Chat), two.back())
    }

    @Test fun theChatTabFromAConversationGoesToTheList() {
        val thread = Navigation(Screen.Chat).open(Screen.ChatThread("a"))
        assertEquals(Navigation(Screen.Chat), thread.open(Screen.Chat))
        // History's Open conversation: the Chat tab, then the conversation above its list.
        val fromHistory = Navigation(Screen.Chat).open(Screen.History).open(Screen.Detail("s1"))
            .open(Screen.Chat).open(Screen.ChatThread("a"))
        assertEquals(thread, fromHistory)
    }

    @Test fun switchingTheLanguageFromAConversationReturnsToTheList() {
        val thread = Navigation(Screen.Chat).open(Screen.ChatThread("a"))
        assertEquals(Navigation(Screen.Chat), thread.afterLanguageSwitch())
        // Anywhere else the path stays.
        val progress = Navigation(Screen.Chat).open(Screen.Progress).open(Screen.Words)
        assertEquals(progress, progress.afterLanguageSwitch())
        assertEquals(Navigation(Screen.Chat), Navigation(Screen.Chat).afterLanguageSwitch())
    }

    @Test fun shortcutsAndSettingsDontOpenAConversation() {
        assertEquals(listOf("lab_chat", "home"), startNavigation(openToChat = true, openSettings = false, tab = Screen.Write).saved)
        assertEquals(listOf("lab_chat", "settings"), startNavigation(openToChat = true, openSettings = true).saved)
        assertEquals(Navigation(), startNavigation(openToChat = false, openSettings = false))
    }
}
