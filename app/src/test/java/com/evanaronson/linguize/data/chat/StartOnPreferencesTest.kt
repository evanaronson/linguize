package com.evanaronson.linguize.data.chat

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** "Start on": its four choices, its default, and how the old "Open to chat" switch carries over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartOnPreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stored = context.getSharedPreferences("lab_chat", Context.MODE_PRIVATE)

    @Before fun clear() {
        stored.edit().clear().commit()
    }

    @Test fun theChoicesAreTheFourTabsInBottomBarOrder() {
        assertEquals(listOf("chat", "notes", "progress", "history"), StartTab.entries.map { it.token })
        assertEquals(StartTab.Chat, StartTab.Default)
    }

    @Test fun nothingSavedStartsOnChat() {
        assertEquals(StartTab.Chat, ChatPreferences(context).startOn)
    }

    @Test fun theOldSwitchCarriesOver() {
        stored.edit().putBoolean("openToChat", false).commit()
        assertEquals(StartTab.Notes, ChatPreferences(context).startOn)
        stored.edit().putBoolean("openToChat", true).commit()
        assertEquals(StartTab.Chat, ChatPreferences(context).startOn)
    }

    @Test fun aChoiceWinsOverTheOldSwitchWhichIsKeptAsItWas() {
        stored.edit().putBoolean("openToChat", false).commit()
        StartTab.entries.forEach { tab ->
            ChatPreferences(context).startOn = tab
            assertEquals(tab, ChatPreferences(context).startOn)
        }
        assertFalse(stored.getBoolean("openToChat", true))
        assertTrue(stored.contains("openToChat"))
    }

    @Test fun anUnknownSavedChoiceFallsBackToTheSwitchThenTheDefault() {
        assertEquals(StartTab.Notes, StartTab.read("unknown", openToChat = false))
        assertEquals(StartTab.Chat, StartTab.read("unknown", openToChat = null))
        assertEquals(StartTab.History, StartTab.read("history", openToChat = false))
        assertEquals(StartTab.Progress, StartTab.read("progress", openToChat = null))
    }
}
