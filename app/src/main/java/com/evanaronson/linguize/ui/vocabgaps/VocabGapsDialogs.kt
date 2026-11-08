package com.evanaronson.linguize.ui.vocabgaps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.VocabView
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale

/** One used word: how often and when, and the writer's own sentences with the word in bold. */
@Composable
internal fun WordDialog(entry: DeckEntry, view: VocabView, onClose: () -> Unit) {
    val use = view.report.used[entry.key] ?: return
    val locale = LocalResources.current.uiLocale
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(entry.lemma) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val summary = listOfNotNull(posLabel(entry.pos)?.let { stringResource(it) }, entry.gloss).joinToString(", ")
                if (summary.isNotEmpty()) Quiet(summary)
                Text(
                    stringResource(
                        R.string.lab_vocab_gaps_word_used,
                        pluralStringResource(R.plurals.lab_vocab_gaps_used_times, use.count, use.count),
                        shortDate(use.lastAt, locale = locale),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (use.bySpelling) Quiet(stringResource(R.string.lab_vocab_gaps_matched_by_spelling))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.lab_vocab_gaps_your_sentences), style = MaterialTheme.typography.titleSmall)
                use.examples.forEach { example ->
                    Spacer(Modifier.height(8.dp))
                    val utterance = view.utterances[example.utterance]
                    if (utterance == null || example.end > utterance.text.length) {
                        Quiet(stringResource(R.string.lab_vocab_gaps_sentence_gone))
                    } else {
                        Text(
                            buildAnnotatedString {
                                append(utterance.text.substring(0, example.start))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(utterance.text.substring(example.start, example.end))
                                }
                                append(utterance.text.substring(example.end))
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Quiet(shortDate(example.at, locale = locale))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.lab_vocab_gaps_close)) } },
    )
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
                        ChoiceRow(languageName(code), null, selected = code == language, onClick = { language = code })
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
internal fun DeleteDialog(onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.lab_vocab_gaps_delete_question)) },
        text = { Text(stringResource(R.string.lab_vocab_gaps_delete_explanation)) },
        confirmButton = { LinguizeButton(stringResource(R.string.lab_vocab_gaps_delete_deck), onDelete, ButtonKind.Destructive) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.lab_vocab_gaps_cancel)) } },
    )
}
