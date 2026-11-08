package com.evanaronson.linguize.data.chat

import android.content.Context
import com.evanaronson.linguize.core.chat.ChatCorpus
import com.evanaronson.linguize.core.corpus.CorpusSource
import com.evanaronson.linguize.core.corpus.Utterance

/**
 * Lab Chat's choices, in their own preferences file (`lab_chat`), never the app's settings:
 * whether the app opens to the chat, whether drafts are checked as the writer types instead
 * of before sending, and the language the writer last chose or used in a conversation.
 */
class ChatPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var openToChat: Boolean
        get() = prefs.getBoolean(OPEN_TO_CHAT, true)
        set(value) = prefs.edit().putBoolean(OPEN_TO_CHAT, value).apply()

    var checkAsYouType: Boolean
        get() = prefs.getBoolean(CHECK_AS_YOU_TYPE, false)
        set(value) = prefs.edit().putBoolean(CHECK_AS_YOU_TYPE, value).apply()

    var lastLanguage: String?
        get() = prefs.getString(LAST_LANGUAGE, null)
        set(value) = prefs.edit().putString(LAST_LANGUAGE, value).apply()

    private companion object {
        const val FILE = "lab_chat"
        const val OPEN_TO_CHAT = "openToChat"
        const val CHECK_AS_YOU_TYPE = "checkAsYouType"
        const val LAST_LANGUAGE = "lastLanguage"
    }
}

/** Lab Chat in the writer corpus: what the writer typed in conversations, read from [store] on demand. */
class ChatCorpusSource(private val store: ChatStore) : CorpusSource {
    override val name = ChatCorpus.SOURCE
    override suspend fun utterances(): List<Utterance> = ChatCorpus.utterances(store.everything())
}
