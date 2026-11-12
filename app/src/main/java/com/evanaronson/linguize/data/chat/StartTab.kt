package com.evanaronson.linguize.data.chat

/**
 * "Start on": the bottom-bar tab the app opens to, and the root Back returns to. Saved by
 * [token] in Lab Chat's preferences ([ChatPreferences.startOn]); Chat by default.
 */
enum class StartTab(val token: String) {
    Chat("chat"),
    Notes("notes"),
    Progress("progress"),
    History("history"),
    ;

    companion object {
        val Default = Chat

        /**
         * The saved choice, else the old "Open to chat" switch it replaced ([openToChat]:
         * on is Chat, off is Notes), else [Default]. An unknown token reads as the switch would.
         */
        fun read(token: String?, openToChat: Boolean?): StartTab =
            entries.firstOrNull { it.token == token }
                ?: when (openToChat) {
                    true -> Chat
                    false -> Notes
                    null -> Default
                }
    }
}
