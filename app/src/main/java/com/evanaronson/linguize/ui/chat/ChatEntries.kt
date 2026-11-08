package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.chat.ChatPreferences
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.SwitchRow

/** Settings, Lab: whether the app opens to the chat, and whether drafts are checked as they are typed. */
@Composable
fun ChatSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { ChatPreferences(context) }
    var openToChat by remember { mutableStateOf(preferences.openToChat) }
    var checkAsYouType by remember { mutableStateOf(preferences.checkAsYouType) }
    SectionHeader(stringResource(R.string.lab_chat_settings_header))
    ListCard(
        {
            SwitchRow(stringResource(R.string.lab_chat_open_to_chat), stringResource(R.string.lab_chat_open_to_chat_summary),
                openToChat) { openToChat = it; preferences.openToChat = it }
        },
        {
            SwitchRow(stringResource(R.string.lab_chat_check_as_you_type), stringResource(R.string.lab_chat_check_as_you_type_summary),
                checkAsYouType) { checkAsYouType = it; preferences.checkAsYouType = it }
        },
    )
}

/** Home's row for the chat, which also stays one tap away when the app opens on Home. */
@Composable
fun ChatHomeEntry(onOpen: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    ListCard({
        ListRow(stringResource(R.string.lab_chat_title), stringResource(R.string.lab_chat_home_subtitle), onClick = onOpen)
    })
}
