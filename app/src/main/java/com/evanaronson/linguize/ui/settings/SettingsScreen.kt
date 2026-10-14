package com.evanaronson.linguize.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.ui.components.Dropdown
import com.evanaronson.linguize.ui.components.MultiSelectDropdown
import com.evanaronson.linguize.ui.components.RadioRow
import com.evanaronson.linguize.ui.components.SectionTitle
import com.evanaronson.linguize.ui.text.descriptionResource
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.text.labelResource

/** Settings content; the activity owns the shared navigation bar and system insets. */
@Composable
fun SettingsScreen(settings: SettingsViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The saved settings load in a moment; showing defaults first would flash the wrong choices.
        val state = settings.state ?: return@Column
        LaunchedEffect(Unit) { settings.showModels() }
        CheckingSection(state, settings)
        PopupSection(state, settings)
        ModelSection(state, settings)
        HistorySection(state, settings)
    }
}

@Composable
private fun CheckingSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle(stringResource(R.string.checking))
    val labels = menuLabels()
    val descriptions = MenuEntry.entries.associateWith { entry ->
        stringResource(R.string.menu_entry_description, labels.getValue(entry),
            entry.language?.displayName() ?: stringResource(R.string.detect_language))
    }
    val punctuationLabels = Punctuation.entries.associateWith { stringResource(it.labelResource) }
    MultiSelectDropdown(
        label = stringResource(R.string.selection_menu_label),
        options = MenuEntry.entries,
        selected = state.menu,
        optionLabel = descriptions::getValue,
        onToggle = settings::toggleMenuEntry,
    )
    if (state.menu.size == 1) {
        Text(
            stringResource(R.string.selection_menu_keep_one),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Dropdown(
        label = stringResource(R.string.punctuation),
        selected = state.punctuation,
        options = Punctuation.entries,
        optionLabel = punctuationLabels::getValue,
        onSelect = settings::setPunctuation,
    )
    Text(
        stringResource(state.punctuation.descriptionResource),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.what_to_suggest), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        Judgments.entries.forEach { option ->
            RadioRow(stringResource(option.labelResource), selected = option == state.judgments, onClick = { settings.setJudgments(option) })
        }
    }
    Text(
        stringResource(R.string.suggestions_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun menuLabels(): Map<MenuEntry, String> = MenuEntry.entries.associateWith { stringResource(it.label) }

@Composable
private fun PopupSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle(stringResource(R.string.popup))
    val labels = PopupPosition.entries.associateWith { position ->
        stringResource(when (position) {
            PopupPosition.Bottom -> R.string.popup_position_bottom
            PopupPosition.Middle -> R.string.popup_position_middle
            PopupPosition.Top -> R.string.popup_position_top
        })
    }
    Dropdown(
        label = stringResource(R.string.popup_position),
        selected = state.popupPosition,
        options = PopupPosition.entries,
        optionLabel = labels::getValue,
        onSelect = settings::setPopupPosition,
    )
}
