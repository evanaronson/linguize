package com.evanaronson.linguize.ui.chat

import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.Origin

/** "Chat" or "Another app": where a saved check came from, for Patterns and Practice examples. */
@StringRes
fun originLabel(origin: String?): Int =
    if (origin == Origin.Chat.token) R.string.lab_chat_origin_chat else R.string.lab_chat_origin_app

/** Practice's "You wrote" label, saying where. */
@StringRes
fun youWroteLabel(origin: String?): Int = when (origin) {
    null -> R.string.lab_practice_you_wrote
    Origin.Chat.token -> R.string.lab_chat_you_wrote_in_chat
    else -> R.string.lab_chat_you_wrote_in_app
}
