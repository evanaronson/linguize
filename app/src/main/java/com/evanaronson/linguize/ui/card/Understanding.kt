package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.ui.components.BackButton

/** Presentation keeps historical assumptions readable without promising a re-check. */
internal data class UnderstandingEntryState(val visible: Boolean, val unresolved: Int, val canClarify: Boolean)

internal fun understandingEntry(
    meaning: String,
    assumptions: List<Assumption>,
    settled: List<Settled>,
    readOnly: Boolean,
): UnderstandingEntryState {
    val unresolved = assumptions.count { a -> settled.none { it.about == a.about } }
    return UnderstandingEntryState(
        visible = meaning.isNotBlank() || assumptions.isNotEmpty() || settled.isNotEmpty(),
        unresolved = unresolved,
        canClarify = !readOnly && unresolved > 0,
    )
}

/** The existing secondary page, with an explicit invitation when clarification is available. */
@Composable
internal fun UnderstandingEntry(entry: UnderstandingEntryState, onOpen: () -> Unit) {
    if (!entry.visible) return
    val label = if (entry.canClarify) {
        pluralStringResource(R.plurals.review_assumptions, entry.unresolved, entry.unresolved)
    } else stringResource(R.string.meaning)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = if (entry.canClarify) label else stringResource(R.string.show_understanding), onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The glyphs only decorate the words, so screen readers skip them.
        Text(
            "ⓘ  ",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * How the text was understood, shown in place of the suggestions: what it means,
 * in the writer's own language, then what was assumed where it was ambiguous.
 * Each assumption is presented as right; alternatives appear only after
 * "Clarify", and picking one checks again with that answer.
 *
 * Both are the AI's reading, and text written by someone else can steer it (a
 * message can ask to be "understood" as something it isn't), so a quiet line at the
 * end says so.
 */
@Composable
internal fun UnderstandingPage(
    meaning: String,
    assumptions: List<Assumption>,
    settled: List<Settled>,
    /** Null on a past check, which cannot be checked again. */
    onSettle: ((String, String) -> Unit)?,
    onBack: () -> Unit,
) {
    BackButton(onBack)
    if (meaning.isNotBlank()) {
        Text(stringResource(R.string.meaning), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.meaning_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        MeaningText(meaning)
    }
    if (assumptions.isNotEmpty() || settled.isNotEmpty()) {
        if (meaning.isNotBlank()) Spacer(Modifier.height(20.dp))
        Assumptions(assumptions, settled, onSettle)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.meaning_caution),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Assumptions(assumptions: List<Assumption>, settled: List<Settled>, onSettle: ((String, String) -> Unit)?) {
    val open = assumptions.filterNot { a -> settled.any { it.about == a.about } }
    var changing by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.assumptions), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.assumptions_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    settled.forEachIndexed { index, answer ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(answer.about)
                Text(answer.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.your_choice), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    open.forEachIndexed { index, assumption ->
        if (index > 0 || settled.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(assumption.about)
                    Text(assumption.assumed, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (assumption.words.isNotBlank()) {
                        Text(
                            stringResource(R.string.quoted_text, assumption.words),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onSettle != null && assumption.alternatives.isNotEmpty() && changing != assumption.about) {
                    val clarify = stringResource(R.string.clarify_about, assumption.about)
                    TextButton(
                        onClick = { changing = assumption.about },
                        modifier = Modifier.semantics { contentDescription = clarify },
                    ) { Text(stringResource(R.string.clarify)) }
                }
            }
            if (onSettle != null && changing == assumption.about) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.what_did_you_mean), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    assumption.alternatives.forEach { alternative ->
                        SuggestionChip(onClick = { onSettle(assumption.about, alternative) }, label = { Text(alternative) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Meaning presentation shared by the reading job and a check's secondary page. */
@Composable
internal fun MeaningText(meaning: String) {
    SelectionContainer {
        Text(
            meaning,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        )
    }
}
