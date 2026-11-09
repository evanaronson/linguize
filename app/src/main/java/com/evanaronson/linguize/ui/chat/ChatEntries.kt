package com.evanaronson.linguize.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.SwitchRow

/** Settings: whether the app starts on Chat or on Notes. Either way the other is in the bottom bar. */
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { ChatPreferences(context) }
    var openToChat by remember { mutableStateOf(preferences.openToChat) }
    SectionHeader(stringResource(R.string.lab_chat_settings_header))
    ListCard({
        SwitchRow(stringResource(R.string.lab_chat_open_to_chat), stringResource(R.string.lab_home_ia_open_to_chat_summary),
            openToChat) { openToChat = it; preferences.openToChat = it }
    })
}

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
