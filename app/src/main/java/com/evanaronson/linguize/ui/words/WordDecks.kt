package com.evanaronson.linguize.ui.words

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.DeckChoice
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.Milestone
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.FilterDropdown
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.theme.Linguize

/*
 * The frequency decks Words measures against: choosing one, the coverage bars, and importing
 * or deleting the writer's own decks. Moved here from Vocabulary gaps.
 */

internal val cardPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** File types the import picker offers. */
internal val DECK_MIME_TYPES = arrayOf(
    "text/*", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
    "application/csv", "application/vnd.ms-excel", "application/octet-stream",
)

@Composable
internal fun deckName(deck: DeckChoice): String =
    if (deck.bundled) stringResource(R.string.lab_vocab_gaps_bundled_deck) else deck.name

/** Which deck coverage is counted against: a filter, so a dropdown like every other one. */
@Composable
internal fun DeckPicker(decks: List<DeckChoice>, selected: String?, onDeck: (String) -> Unit) {
    if (decks.size < 2) return
    val chosen = decks.firstOrNull { it.id == selected } ?: decks.first()
    FilterDropdown(
        selected = chosen,
        options = decks,
        label = { deck ->
            deckName(deck) + " · " + pluralStringResource(R.plurals.lab_vocab_gaps_deck_size, deck.size, deck.size)
        },
        description = stringResource(R.string.lab_vocab_gaps_deck),
        onSelect = { onDeck(it.id) },
        labelled = true,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun MilestoneBar(milestone: Milestone, isAll: Boolean) {
    val label = stringResource(if (isAll) R.string.lab_vocab_gaps_all else R.string.lab_vocab_gaps_top, milestone.top)
    val description = stringResource(R.string.lab_vocab_gaps_bar_description, label, milestone.used, milestone.top)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.lab_vocab_gaps_used_of, milestone.used, milestone.top),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Linguize.colors.rowDivider)) {
            val fraction = if (milestone.top == 0) 0f else milestone.used.toFloat() / milestone.top
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

/** The writer's imported decks, with Delete, and Import a deck. */
@Composable
internal fun YourDecks(state: WordsState, onImport: () -> Unit, onDelete: (String) -> Unit) {
    SectionHeader(stringResource(R.string.lab_vocab_gaps_your_decks))
    SectionCard {
        if (state.imported.isEmpty()) {
            ListRow(stringResource(R.string.lab_vocab_gaps_no_decks), stringResource(R.string.lab_vocab_gaps_import_caption))
        }
        state.imported.forEachIndexed { index, deck ->
            if (index > 0) RowDivider()
            val description = stringResource(R.string.lab_vocab_gaps_delete_deck_description, deck.name)
            ListRow(
                deck.name,
                stringResource(
                    R.string.lab_vocab_gaps_deck_summary,
                    languageLabel(deck.language),
                    pluralStringResource(R.plurals.lab_vocab_gaps_deck_size, deck.size, deck.size),
                ),
            ) {
                LinguizeButton(
                    stringResource(R.string.lab_vocab_gaps_delete_deck), { onDelete(deck.id) }, ButtonKind.Destructive,
                    Modifier.semantics { contentDescription = description },
                )
            }
        }
        RowDivider()
        Column(Modifier.padding(vertical = 12.dp)) {
            LinguizeButton(stringResource(R.string.lab_vocab_gaps_import), onImport, ButtonKind.Tonal)
            state.importMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Quiet(importMessage(message))
            }
        }
    }
}

@Composable
private fun importMessage(message: ImportMessage): String = when (message) {
    is ImportMessage.Imported -> pluralStringResource(R.plurals.lab_vocab_gaps_imported, message.words, message.words)
    is ImportMessage.Failed -> stringResource(
        if (message.reason == DeckParse.Reason.Empty) R.string.lab_vocab_gaps_import_empty else R.string.lab_vocab_gaps_import_no_words,
    )
    ImportMessage.Unreadable -> stringResource(R.string.lab_vocab_gaps_import_unreadable)
    ImportMessage.TooLarge -> stringResource(R.string.lab_vocab_gaps_import_too_large)
}

/** Names a picked file and chooses the language of its words. */
@Composable
internal fun ImportDialog(
    pending: PendingImport,
    languages: List<String>,
    onImport: (name: String, language: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(pending) { mutableStateOf(pending.suggestedName) }
    var language by rememberSaveable(pending) { mutableStateOf(languages.firstOrNull() ?: "es") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.lab_vocab_gaps_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LinguizeTextField(name, { name = it }, label = stringResource(R.string.lab_vocab_gaps_import_name), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.lab_vocab_gaps_import_language), style = MaterialTheme.typography.labelMedium)
                Column(Modifier.selectableGroup()) {
                    languages.forEach { code ->
                        ChoiceRow(languageLabel(code), null, selected = code == language, onClick = { language = code })
                    }
                }
            }
        },
        confirmButton = {
            LinguizeButton(stringResource(R.string.lab_vocab_gaps_import_confirm), { onImport(name, language) }, ButtonKind.Filled)
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.lab_vocab_gaps_cancel)) } },
    )
}

@Composable
internal fun DeleteDeckDialog(onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.lab_vocab_gaps_delete_question)) },
        text = { Text(stringResource(R.string.lab_vocab_gaps_delete_explanation)) },
        confirmButton = { LinguizeButton(stringResource(R.string.lab_vocab_gaps_delete_deck), onDelete, ButtonKind.Destructive) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.lab_vocab_gaps_cancel)) } },
    )
}
