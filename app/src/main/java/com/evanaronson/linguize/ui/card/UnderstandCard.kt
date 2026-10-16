package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.understand.UnderstandState

/** Translation, original and optional source-anchored notes, without correction controls. */
@Composable
fun UnderstandCard(
    state: UnderstandState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onBackToCheck: (() -> Unit)? = null,
) {
    ResultSurface(modifier) {
        onBackToCheck?.let { back ->
            TextButton(onClick = back) { Text(stringResource(R.string.back_to_check)) }
        }
        when (state) {
            UnderstandState.ReadingClipboard -> Loading("", stringResource(R.string.reading_copied_text))
            is UnderstandState.Message -> {
                Outcome(Mark.Unsure, stringResource(
                    if (state.unavailable) R.string.clipboard_unavailable else R.string.shortcut_no_input,
                ))
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
            is UnderstandState.Loading -> {
                Text(stringResource(R.string.copied_text), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Loading(state.text, stringResource(R.string.understanding))
            }
            is UnderstandState.Done -> {
                Text(stringResource(R.string.translation), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                MeaningText(state.result.translation)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.copied_text), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(state.text, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.result.notes.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.understanding_notes), style = MaterialTheme.typography.titleMedium)
                    for (note in state.result.notes) {
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Text(note.quote, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer { Text(note.explanation, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.meaning_caution), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UnderstandState.Failed -> Failure(
                state.failure.reason, state.failure.userMessage, state.failure.detail, onRetry, onOpenSettings,
            )
        }
    }
}
