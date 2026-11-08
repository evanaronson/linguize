package com.evanaronson.linguize.ui.home

import android.content.Context
import com.evanaronson.linguize.data.chat.ChatPreferences

/**
 * Where the app starts. With "Open to chat" on (the default) the chat is the start
 * destination; off, Write is. Every other tab sits above the start, so Back returns to it.
 * [tab] (the Try it shortcut asks for Write) opens one tab above the start, and
 * [openSettings] (a popup's "Open settings") puts Settings on top.
 */
internal fun startNavigation(openToChat: Boolean, openSettings: Boolean, tab: Screen? = null): Navigation {
    val root = if (openToChat) Navigation(Screen.Chat) else Navigation()
    val opened = tab?.takeIf { it.isTab }?.let(root::open) ?: root
    return if (openSettings) opened.open(Screen.Settings) else opened
}

/** [startNavigation] with the saved Lab choice. */
internal fun startNavigation(context: Context, openSettings: Boolean, tab: Screen? = null): Navigation =
    startNavigation(runCatching { ChatPreferences(context).openToChat }.getOrDefault(true), openSettings, tab)
