package com.evanaronson.linguize.ui.appvoice

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.evanaronson.linguize.ui.history.AppLabels
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.AppRule
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.text.descriptionResource
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.text.labelResource

/** The Settings row that opens Per-app voice, with how many apps have a rule. */
@Composable
fun AppVoiceSettingsRow(voice: AppVoiceSettingsViewModel, onOpen: () -> Unit) {
    val count = voice.entries?.count { it.rule != null } ?: 0
    val summary = if (count == 0) stringResource(R.string.lab_app_voice_summary_none)
    else pluralStringResource(R.plurals.lab_app_voice_summary, count, count)
    Spacer(Modifier.height(8.dp))
    ListCard({ ListRow(stringResource(R.string.lab_app_voice_title), summary, onClick = onOpen) })
}

/** Per-app voice: apps with rules, recent apps from History, and installed messaging apps. */
@Composable
fun AppVoiceListScreen(voice: AppVoiceSettingsViewModel, onOpen: (String) -> Unit) {
    val entries = voice.entries ?: return
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        CardNote(stringResource(R.string.lab_app_voice_intro))
        if (entries.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            CardNote(stringResource(R.string.lab_app_voice_empty))
        }
        AppSection.entries.forEach { section ->
            val apps = entries.filter { it.section == section }
            if (apps.isEmpty()) return@forEach
            SectionHeader(stringResource(section.title))
            ListCard(*apps.map { entry ->
                @Composable {
                    val name = entry.name ?: entry.app
                    ListRow(
                        name, ruleSummary(entry.rule),
                        onClick = { onOpen(entry.app) },
                        onClickLabel = stringResource(R.string.lab_app_voice_open, name),
                    )
                }
            }.toTypedArray())
        }
        if (entries.any { it.rule != null }) {
            LinguizeButton(
                stringResource(R.string.lab_app_voice_reset_all), { confirmingReset = true }, ButtonKind.Destructive,
                Modifier.padding(top = 16.dp),
            )
        }
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.lab_app_voice_reset_all_question)) },
            text = { Text(stringResource(R.string.lab_app_voice_reset_all_description)) },
            confirmButton = {
                LinguizeButton(stringResource(R.string.lab_app_voice_reset), onClick = {
                    confirmingReset = false
                    voice.resetAll()
                }, kind = ButtonKind.Destructive)
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** One app's rule: language, register and what to suggest, each defaulting to Settings. */
@Composable
fun AppVoiceRuleScreen(voice: AppVoiceSettingsViewModel, app: String) {
    // Read through entries so edits recompose.
    val rule = voice.entries?.firstOrNull { it.app == app }?.rule ?: voice.rule(app)
    val same = stringResource(R.string.lab_app_voice_same_as_settings)
    ScreenColumn {
        SectionHeader(stringResource(R.string.lab_app_voice_language))
        Choices(listOf(null) + Language.all, { it == rule?.language }, {
            it?.displayName() ?: stringResource(R.string.auto_detect)
        }, { if (it == null) stringResource(R.string.detect_language) else null }) { voice.setLanguage(app, it) }
        CardNote(stringResource(R.string.lab_app_voice_language_note))

        SectionHeader(stringResource(R.string.writing_register))
        Choices(listOf(null) + WritingRegister.entries, { it == rule?.register }, {
            it?.let { register -> stringResource(register.labelResource) } ?: same
        }, { it?.let { register -> stringResource(register.descriptionResource) } }) { voice.setRegister(app, it) }

        SectionHeader(stringResource(R.string.what_to_suggest))
        Choices(listOf(null) + Judgments.entries, { it == rule?.judgments }, {
            it?.let { judgments -> stringResource(judgments.labelResource) } ?: same
        }, { null }) { voice.setJudgments(app, it) }

        if (rule != null) {
            LinguizeButton(
                stringResource(R.string.lab_app_voice_reset_rule), { voice.reset(app) }, ButtonKind.Destructive,
                Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** The page title: Per-app voice, or for one app its name (the package when it isn't installed). */
@Composable
fun appVoiceTitle(app: String?): String {
    if (app == null) return stringResource(R.string.lab_app_voice_title)
    val packages = LocalContext.current.packageManager
    // One cached package-manager lookup per app; the page is opened from a list that already named it.
    return remember(app) { AppLabels(packages).of(app) } ?: app
}

@Composable
private fun <T> Choices(
    options: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    onSelect: (T) -> Unit,
) {
    SectionCard(Modifier.selectableGroup()) {
        options.forEachIndexed { index, option ->
            if (index > 0) RowDivider()
            ChoiceRow(label(option), description(option), selected(option), { onSelect(option) })
        }
    }
}

/** "Catalan, Formal, Fixes only", or "Usual settings" when the app has no rule. */
@Composable
private fun ruleSummary(rule: AppRule?): String {
    if (rule == null) return stringResource(R.string.lab_app_voice_usual)
    val parts = listOfNotNull(
        rule.language?.displayName(),
        rule.register?.let { stringResource(it.labelResource) },
        rule.judgments?.let { stringResource(it.labelResource) },
    )
    return parts.joinToString(stringResource(R.string.list_separator))
}

private val AppSection.title: Int
    get() = when (this) {
        AppSection.WithRule -> R.string.lab_app_voice_with_rule
        AppSection.Recent -> R.string.lab_app_voice_recent
        AppSection.Messaging -> R.string.lab_app_voice_messaging
    }
