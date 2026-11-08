package com.evanaronson.linguize.ui.gaps

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.lab.Fill
import com.evanaronson.linguize.core.lab.GapFill
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Loading
import com.evanaronson.linguize.ui.card.Outcome
import com.evanaronson.linguize.ui.card.PageHeader
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.theme.Linguize

/** What the Fill the gaps card's buttons do; supplied by its host. Null actions are hidden. */
class GapActions(
    val onChoose: (fill: Int, option: Int) -> Unit,
    val onRetry: () -> Unit,
    val onCopy: (String) -> Unit,
    /** Puts the result back in the source app; null where it can't be replaced. */
    val onPutBack: (() -> Unit)? = null,
    /** Puts the result in Home's box. */
    val onUse: (() -> Unit)? = null,
    /** Runs the normal check on the result. */
    val onCheck: (() -> Unit)? = null,
    /** Returns to the check this fill was opened from. */
    val onBack: (() -> Unit)? = null,
    val onOpenSettings: (() -> Unit)? = null,
)

/**
 * The filled message, each fill highlighted, then every fill with the writer's own phrase,
 * a short note and other ways to say it. Floating in a popup or flat inside a screen
 * ([scrollable] false).
 */
@Composable
fun GapFillCard(state: GapState, actions: GapActions, modifier: Modifier = Modifier, scrollable: Boolean = true) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = actions.onBack != null) { actions.onBack?.invoke() }
    }
    ResultSurface(modifier, scrollable) {
        actions.onBack?.let { PageHeader(stringResource(R.string.lab_gap_filler_title), it) }
        when (state) {
            is GapState.Loading -> Loading(state.text, stringResource(R.string.lab_gap_filler_filling))
            is GapState.Failed -> Failure(
                state.failure.reason, state.failure.userMessage, state.failure.detail,
                actions.onRetry, actions.onOpenSettings,
            )
            is GapState.Done -> Filled(state.result, actions)
        }
    }
}

@Composable
private fun Filled(result: GapFill, actions: GapActions) {
    if (result.fills.isEmpty()) {
        Outcome(stringResource(R.string.lab_gap_filler_nothing), stringResource(R.string.lab_gap_filler_nothing_hint))
        return
    }
    val count = result.fills.size
    Outcome(pluralStringResource(R.plurals.lab_gap_filler_filled_count, count, count), success = true)
    Spacer(Modifier.height(12.dp))
    val highlight = SpanStyle(fontWeight = FontWeight.SemiBold, background = Linguize.colors.panel)
    SelectionContainer {
        Text(highlighted(result, highlight), style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.onPutBack?.let { LinguizeButton(stringResource(R.string.lab_gap_filler_put_back), it, ButtonKind.Filled) }
        actions.onUse?.let { LinguizeButton(stringResource(R.string.lab_gap_filler_use), it, ButtonKind.Filled) }
        actions.onCheck?.let {
            LinguizeButton(stringResource(R.string.lab_gap_filler_check), it,
                if (actions.onPutBack == null && actions.onUse == null) ButtonKind.Filled else ButtonKind.Tonal)
        }
        LinguizeButton(stringResource(R.string.lab_gap_filler_copy), { actions.onCopy(result.text) }, ButtonKind.Text)
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.lab_gap_filler_fills), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    result.fills.forEachIndexed { index, fill ->
        Spacer(Modifier.height(12.dp))
        FillDetail(fill) { option -> actions.onChoose(index, option) }
    }
}

/** The result with each fill set apart; the words around them are the writer's own. */
internal fun highlighted(result: GapFill, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    for (piece in result.pieces) {
        if (piece.fill != null) withStyle(style) { append(piece.text) } else append(piece.text)
    }
}

@Composable
private fun FillDetail(fill: Fill, onChoose: (option: Int) -> Unit) {
    val spoken = stringResource(R.string.lab_gap_filler_becomes, fill.original, fill.text)
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        // "pick me up" then "recogerme": read as one sentence, in words, not an arrow.
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            Text(fill.original, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fill.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        if (fill.note.isNotEmpty()) {
            Text(fill.note, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
    if (fill.options.size > 1) {
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.lab_gap_filler_other_ways), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            fill.options.forEachIndexed { option, text ->
                val inUse = option == fill.chosen
                val label = stringResource(R.string.lab_gap_filler_use_option, text)
                val inUseLabel = stringResource(R.string.lab_gap_filler_in_use)
                LinguizeButton(
                    text,
                    { onChoose(option) },
                    if (inUse) ButtonKind.Tonal else ButtonKind.Outlined,
                    modifier = Modifier.semantics {
                        contentDescription = if (inUse) text else label
                        if (inUse) stateDescription = inUseLabel
                    },
                )
            }
        }
    }
}
