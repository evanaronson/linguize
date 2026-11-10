package com.evanaronson.linguize.data.chat

import android.content.Context

/**
 * Lab Chat's choices, in their own preferences file (`lab_chat`), never the app's settings:
 * the tab the app starts on, and the language the writer last chose or used in a
 * conversation.
 */
class ChatPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * "Start on". Until it's chosen, it follows the old "Open to chat" switch (on: Chat, off:
     * Notes), whose stored value is kept as it was.
     */
    var startOn: StartTab
        get() = StartTab.read(
            prefs.getString(START_ON, null),
            if (prefs.contains(OPEN_TO_CHAT)) prefs.getBoolean(OPEN_TO_CHAT, true) else null,
        )
        set(value) = prefs.edit().putString(START_ON, value.token).apply()

    var lastLanguage: String?
        get() = prefs.getString(LAST_LANGUAGE, null)
        set(value) = prefs.edit().putString(LAST_LANGUAGE, value).apply()

    /** Whether the writer has flipped a reply to its translation once: the "Tap to translate" hint is gone for good. */
    var translateHintSeen: Boolean
        get() = prefs.getBoolean(TRANSLATE_HINT_SEEN, false)
        set(value) = prefs.edit().putBoolean(TRANSLATE_HINT_SEEN, value).apply()

    private companion object {
        const val FILE = "lab_chat"
        const val START_ON = "startOn"
        /** The old "Open to chat" switch: read until "Start on" is chosen; never written. */
        const val OPEN_TO_CHAT = "openToChat"
        const val LAST_LANGUAGE = "lastLanguage"
        const val TRANSLATE_HINT_SEEN = "translateHintSeen"
    }
}
