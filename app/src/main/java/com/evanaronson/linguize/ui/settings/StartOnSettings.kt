package com.evanaronson.linguize.ui.settings

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
import com.evanaronson.linguize.data.chat.StartTab
import com.evanaronson.linguize.ui.components.ChoicePage
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionHeader

/** Settings: "Start on", the tab the app opens to, showing the current one; it opens [SettingsPage.StartOn]. */
@Composable
internal fun StartOnSection(onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    // Read whenever Settings comes back into view, so it's current on the way back from the page.
    val start = remember(context) { ChatPreferences(context).startOn }
    SectionHeader(stringResource(R.string.lab_settings_start_header))
    ListCard({
        ListRow(stringResource(SettingsPage.StartOn.title), stringResource(start.labelResource),
            onClick = { onOpen(SettingsPage.StartOn) })
    })
}

/** The "Start on" page: the four tabs, each with what it opens to. */
@Composable
internal fun StartOnPage() {
    val context = LocalContext.current
    val preferences = remember(context) { ChatPreferences(context) }
    var start by remember { mutableStateOf(preferences.startOn) }
    ChoicePage(
        StartTab.entries, { it == start }, { stringResource(it.labelResource) },
        { stringResource(it.descriptionResource) },
        { start = it; preferences.startOn = it },
        note = stringResource(R.string.lab_settings_start_note),
    )
}

/** The tab's name, as in the bottom bar. */
@get:StringRes
internal val StartTab.labelResource: Int
    get() = when (this) {
        StartTab.Chat -> R.string.lab_chat_title
        StartTab.Notes -> R.string.lab_consistency_notes
        StartTab.Progress -> R.string.lab_home_ia_progress
        StartTab.History -> R.string.history
    }

/** What the app opens to on that tab, under its name on the page. */
@get:StringRes
internal val StartTab.descriptionResource: Int
    get() = when (this) {
        StartTab.Chat -> R.string.lab_settings_start_chat
        StartTab.Notes -> R.string.lab_settings_start_notes
        StartTab.Progress -> R.string.lab_settings_start_progress
        StartTab.History -> R.string.lab_settings_start_history
    }
