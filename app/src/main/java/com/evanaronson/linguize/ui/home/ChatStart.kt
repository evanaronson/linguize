package com.evanaronson.linguize.ui.home

import android.content.Context
import com.evanaronson.linguize.data.chat.ChatPreferences

/**
 * Where the app starts. With "Open to chat" on (the default) the Chat tab is the start
 * destination, and the app opens straight into the current conversation above its list
 * ([Screen.ChatThread] with no id: resolved when shown, and closed to the list when there is
 * none), so the list is one Back away. Off, Notes is the start. Every other tab sits above the
 * start, so Back returns to it. [tab] (the Notes shortcut) opens one tab above the start, and
 * [openSettings] (a popup's "Open settings") puts Settings on top; neither opens a conversation.
 */
internal fun startNavigation(openToChat: Boolean, openSettings: Boolean, tab: Screen? = null): Navigation {
    val root = if (openToChat) Navigation(Screen.Chat) else Navigation()
    val requested = tab?.takeIf { it.isTab }
    val opened = when {
        requested != null -> root.open(requested)
        openToChat && !openSettings -> root.open(Screen.ChatThread(null))
        else -> root
    }
    return if (openSettings) opened.open(Screen.Settings) else opened
}

/** [startNavigation] with the saved Lab choice. */
internal fun startNavigation(context: Context, openSettings: Boolean, tab: Screen? = null): Navigation =
    startNavigation(runCatching { ChatPreferences(context).openToChat }.getOrDefault(true), openSettings, tab)
