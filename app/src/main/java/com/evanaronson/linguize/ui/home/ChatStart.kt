package com.evanaronson.linguize.ui.home

import android.content.Context
import com.evanaronson.linguize.data.chat.ChatPreferences

/**
 * Lab Chat: where the app starts. With "Open to chat" on (the default) the chat is the root
 * and Home sits above it, so Back from Home returns to the chat; off, Home is the root as
 * before. [openSettings] (a popup's "Open settings") puts Settings on top of either.
 */
internal fun startNavigation(openToChat: Boolean, openSettings: Boolean): Navigation {
    val root = if (openToChat) Navigation(Screen.Chat) else Navigation()
    return if (openSettings) root.open(Screen.Settings) else root
}

/** [startNavigation] with the saved Lab choice. */
internal fun startNavigation(context: Context, openSettings: Boolean): Navigation =
    startNavigation(runCatching { ChatPreferences(context).openToChat }.getOrDefault(true), openSettings)
