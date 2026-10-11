package com.evanaronson.linguize.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.ui.components.BackButton
import com.evanaronson.linguize.ui.components.Dropdown
import com.evanaronson.linguize.ui.components.MultiSelectDropdown
import com.evanaronson.linguize.ui.components.RadioRow
import com.evanaronson.linguize.ui.components.SectionTitle

/** Settings can be entered from Home or a popup; Back returns, Home opens the app. */
@Composable
fun SettingsScreen(settings: SettingsViewModel, onBack: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton(onBack)
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onHome) { Text("Home") }
        }
        SettingsContent(settings)
    }
}

@Composable
private fun SettingsContent(settings: SettingsViewModel) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The saved settings load in a moment; showing defaults first would flash the wrong choices.
        val state = settings.state ?: return@Column
        LaunchedEffect(Unit) { settings.showModels() }
        CheckingSection(state, settings)
        ModelSection(state, settings)
        HistorySection(state, settings)
        OtherAppsSection()
    }
}

@Composable
private fun CheckingSection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle("Checking")
    val labels = menuLabels()
    MultiSelectDropdown(
        label = "Show in selection menu",
        options = MenuEntry.entries,
        selected = state.menu,
        optionLabel = { entry -> "${labels.getValue(entry)} (${entry.language?.name ?: "detects the language"})" },
        onToggle = settings::toggleMenuEntry,
    )
    if (state.menu.size == 1) {
        Text(
            "Keep at least one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Dropdown(
        label = "Punctuation",
        selected = state.punctuation.label,
        options = Punctuation.entries,
        optionLabel = { it.label },
        onSelect = settings::setPunctuation,
    )
    Text(
        state.punctuation.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("What to suggest", style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        Judgments.entries.forEach { option ->
            RadioRow(option.label, selected = option == state.judgments, onClick = { settings.setJudgments(option) })
        }
    }
    Text(
        "Fixes correct mistakes. Rewordings make it sound more natural.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun menuLabels(): Map<MenuEntry, String> = MenuEntry.entries.associateWith { stringResource(it.label) }

