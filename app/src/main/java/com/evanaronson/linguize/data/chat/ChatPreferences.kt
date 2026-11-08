package com.evanaronson.linguize.data.chat

import android.content.Context

/**
 * Lab Chat's choices, in their own preferences file (`lab_chat`), never the app's settings:
 * whether the app opens to the chat, and the language the writer last chose or used in a
 * conversation.
 */
class ChatPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var openToChat: Boolean
        get() = prefs.getBoolean(OPEN_TO_CHAT, true)
        set(value) = prefs.edit().putBoolean(OPEN_TO_CHAT, value).apply()

    var lastLanguage: String?
        get() = prefs.getString(LAST_LANGUAGE, null)
        set(value) = prefs.edit().putString(LAST_LANGUAGE, value).apply()

    private companion object {
        const val FILE = "lab_chat"
        const val OPEN_TO_CHAT = "openToChat"
        const val LAST_LANGUAGE = "lastLanguage"
    }
}
