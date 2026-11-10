package com.evanaronson.linguize.ui.home

import android.content.Context
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.data.chat.StartTab

/**
 * Where the app starts: the "Start on" tab ([StartTab], Chat by default) is the start
 * destination, the root of every path, so Back from any other tab returns to it. With Chat,
 * the app opens straight into the current conversation above its list ([Screen.ChatThread]
 * with no id: resolved when shown, and closed to the list when there is none), so the list is
 * one Back away. Notes likewise opens straight into the current note ([Screen.Note] with no
 * id), the list one Back away. [tab] (the Notes shortcut) opens one tab above the start, Notes
 * into its current note; [openSettings] (a popup's "Open settings") puts Settings on top and
 * opens no conversation or note.
 */
internal fun startNavigation(start: StartTab, openSettings: Boolean, tab: Screen? = null): Navigation {
    val root = Navigation(start.screen)
    val requested = tab?.takeIf { it.isTab }
    val opened = when {
        requested == Screen.Notes -> root.open(Screen.Notes).open(Screen.Note(null))
        requested != null -> root.open(requested)
        openSettings -> root
        start == StartTab.Chat -> root.open(Screen.ChatThread(null))
        start == StartTab.Notes -> root.open(Screen.Note(null))
        else -> root
    }
    return if (openSettings) opened.open(Screen.Settings) else opened
}

/** The old "Open to chat" switch: on starts on Chat, off on Notes. */
internal fun startNavigation(openToChat: Boolean, openSettings: Boolean, tab: Screen? = null): Navigation =
    startNavigation(if (openToChat) StartTab.Chat else StartTab.Notes, openSettings, tab)

/** [startNavigation] with the saved "Start on". */
internal fun startNavigation(context: Context, openSettings: Boolean, tab: Screen? = null): Navigation =
    startNavigation(runCatching { ChatPreferences(context).startOn }.getOrDefault(StartTab.Default), openSettings, tab)

/** The tab a [StartTab] names. */
internal val StartTab.screen: Screen
    get() = when (this) {
        StartTab.Chat -> Screen.Chat
        StartTab.Notes -> Screen.Notes
        StartTab.Progress -> Screen.Progress
        StartTab.History -> Screen.History
    }
