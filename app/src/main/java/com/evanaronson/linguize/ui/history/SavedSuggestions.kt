package com.evanaronson.linguize.ui.history

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.ui.card.AssessmentDetails
import com.evanaronson.linguize.ui.card.AssumptionItem
import com.evanaronson.linguize.ui.card.linkedAssumptions
import com.evanaronson.linguize.ui.card.visibleValue

/** History-only recovery: saved decisions remain readable without raw answers or valid spans. */
internal fun savedSuggestions(detail: SessionDetail): List<SuggestionRecord> {
    val rows = detail.suggestions.filter { it.decision != Decision.Superseded }
    val attempt = rows.maxOfOrNull { it.attempt } ?: return emptyList()
    return rows.filter { it.attempt == attempt }
}

@StringRes
internal fun savedSuggestionState(decision: Decision, hasFinalText: Boolean): Int = when (decision) {
    Decision.Accepted -> if (hasFinalText) R.string.history_applied else R.string.history_application_not_recorded
    Decision.Ignored, Decision.Undone -> R.string.history_not_applied
    Decision.Copied -> R.string.history_copied
    Decision.Retired -> R.string.history_covered
    Decision.Superseded -> R.string.history_not_applied // Excluded by savedSuggestions.
}

/** Optional same-attempt replay context; never infer dependencies from row order or text alone. */
internal fun savedSuggestionAssumptions(
    row: SuggestionRecord,
    reviewed: CheckResult.Reviewed?,
    settled: List<Settled>,
): List<Assumption> {
    if (reviewed == null) return emptyList()
    val edit = reviewed.revision.edits.singleOrNull {
        it.kind == row.kind && it.start == row.start && it.end == row.end &&
            it.from == row.fromText && it.replacement == row.toText
    } ?: return emptyList()
    return linkedAssumptions(edit.assumptionIndices, reviewed.assumptions, settled)
}

@Composable
internal fun SavedSuggestions(
    sessionId: String,
    rows: List<SuggestionRecord>,
    hasFinalText: Boolean,
    reviewed: CheckResult.Reviewed? = null,
    settled: List<Settled> = emptyList(),
) {
    Text(stringResource(R.string.history_suggestions), style = MaterialTheme.typography.titleMedium)
    if (rows.isEmpty()) {
        Text(stringResource(R.string.history_no_suggestions), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rows.forEach { row ->
        key(row.id) {
            SavedSuggestion(sessionId, row, hasFinalText, savedSuggestionAssumptions(row, reviewed, settled))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SavedSuggestion(
    sessionId: String,
    row: SuggestionRecord,
    hasFinalText: Boolean,
    assumptions: List<Assumption>,
) {
    var expanded by rememberSaveable(sessionId, row.id) { mutableStateOf(false) }
    val action = stringResource(if (expanded) R.string.history_hide_explanation else R.string.history_show_explanation)
    val expansion = stringResource(if (expanded) R.string.history_expanded else R.string.history_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .semantics { stateDescription = expansion }
                .clickable(onClickLabel = action) { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Use literal saved strings, never slice source text using historical spans.
                val from = row.fromText.visibleValue()
                val to = row.toText.visibleValue()
                val spokenReplacement = if (row.fromText.isNotEmpty() && row.toText.isNotEmpty()) {
                    stringResource(R.string.change_becomes, from, to)
                } else null
                val replacement = when {
                    row.fromText.isEmpty() && row.toText.isEmpty() -> stringResource(R.string.history_no_suggestions)
                    row.fromText.isEmpty() -> stringResource(R.string.change_add, to)
                    row.toText.isEmpty() -> stringResource(R.string.change_remove, from)
                    else -> stringResource(R.string.change_replacement, from, to)
                }
                Text(
                    replacement,
                    modifier = if (spokenReplacement != null) {
                        Modifier.clearAndSetSemantics { contentDescription = spokenReplacement }
                    } else Modifier,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(stringResource(savedSuggestionState(row.decision, hasFinalText)),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "▴" else "▾", modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics {})
        }
        if (expanded) {
            Text(row.why?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_no_explanation),
                style = MaterialTheme.typography.bodyMedium)
            AssessmentDetails(row.assessment)
            if (assumptions.isNotEmpty()) {
                Text(stringResource(R.string.depends_on), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                assumptions.forEach { AssumptionItem(it, onSettle = null) }
            }
        }
    }
}
