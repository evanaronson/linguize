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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.Checker
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
    val unresolved = assumptions.count { a ->
        hasUniqueLabel(a, assumptions) && settled.none { it.answers(a.about) }
    }
    return UnderstandingEntryState(
        visible = !readOnly || meaning.isNotBlank() || assumptions.isNotEmpty() || settled.isNotEmpty(),
        unresolved = unresolved,
        canClarify = !readOnly,
    )
}

/** The existing secondary page, with an explicit invitation when clarification is available. */
@Composable
internal fun UnderstandingEntry(entry: UnderstandingEntryState, onOpen: () -> Unit) {
    if (!entry.visible) return
    val label = if (entry.canClarify && entry.unresolved > 0) {
        pluralStringResource(R.plurals.review_assumptions, entry.unresolved, entry.unresolved)
    } else stringResource(R.string.meaning)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = if (entry.canClarify && entry.unresolved > 0) label else stringResource(R.string.show_understanding), onClick = onOpen)
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
 * How the text was understood, followed by the writer's clarification and any
 * assumptions. Live checks can always be clarified, even without a supplied meaning.
 */
@Composable
internal fun UnderstandingPage(
    meaning: String,
    assumptions: List<Assumption>,
    settled: List<Settled>,
    /** Null on a past check, which cannot be checked again. */
    onSettle: ((String, String) -> Unit)?,
    /** Null on a past check, whose answers remain evidence of that request. */
    onRemoveChoice: ((String) -> Unit)?,
    onBack: () -> Unit,
    onClarify: ((String) -> Unit)? = null,
    onRemoveClarification: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackButton {
        focus.clearFocus()
        keyboard?.hide()
        onBack()
    }
    MeaningSection(meaning, settled, onClarify, onRemoveClarification)
    val assumptionAnswers = settled.filterNot { it.isMeaning }
    if (assumptions.isNotEmpty() || assumptionAnswers.isNotEmpty()) {
        if (meaning.isNotBlank() || settled.any { it.isMeaning } || onClarify != null) {
            Spacer(Modifier.height(20.dp))
        }
        Assumptions(assumptions, assumptionAnswers, onSettle, onRemoveChoice)
    }
}

/** Shared by Meaning and an unclear result, so either can accept the writer's intent. */
@Composable
internal fun MeaningSection(
    meaning: String,
    settled: List<Settled>,
    onClarify: ((String) -> Unit)?,
    onRemoveClarification: (() -> Unit)?,
) {
    val clarification = settled.firstOrNull { it.isMeaning }
    if (meaning.isBlank() && clarification == null && onClarify == null) return
    var changing by rememberSaveable(meaning, clarification?.answer) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.meaning), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f))
        if (onClarify != null && !changing) {
            val clarify = stringResource(R.string.clarify_meaning)
            TextButton(
                onClick = { changing = true },
                modifier = Modifier.semantics { contentDescription = clarify },
            ) { Text(stringResource(R.string.clarify)) }
        }
    }
    if (meaning.isNotBlank()) {
        Text(
            stringResource(R.string.meaning_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        MeaningText(meaning)
    }
    if (clarification != null) {
        Spacer(Modifier.height(12.dp))
        Label(stringResource(R.string.your_clarification))
        Text(clarification.answer, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold)
        if (onRemoveClarification != null && !changing) {
            TextButton(onClick = onRemoveClarification) { Text(stringResource(R.string.remove_clarification)) }
        }
    }
    if (onClarify != null && changing) {
        Spacer(Modifier.height(8.dp))
        ClarificationEditor(
            initialAnswer = clarification?.answer.orEmpty(),
            onSubmit = { answer ->
                changing = false
                onClarify(answer)
            },
            onCancel = { changing = false },
        )
    }
}

@Composable
private fun Assumptions(
    assumptions: List<Assumption>,
    settled: List<Settled>,
    onSettle: ((String, String) -> Unit)?,
    onRemoveChoice: ((String) -> Unit)?,
) {
    val open = assumptions.filterNot { a -> settled.any { it.answers(a.about) } }
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
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.your_choice), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (onRemoveChoice != null) {
                    val remove = stringResource(R.string.remove_choice_about, answer.about)
                    TextButton(
                        onClick = { onRemoveChoice(answer.about) },
                        modifier = Modifier.semantics { contentDescription = remove },
                    ) { Text(stringResource(R.string.remove_choice)) }
                }
            }
        }
    }
    open.forEachIndexed { index, assumption ->
        if (index > 0 || settled.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AssumptionItem(assumption, onSettle.takeIf { hasUniqueLabel(assumption, assumptions) })
    }
}

/** The same question and answer action, whether shown on Meaning or beside a suggestion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssumptionItem(assumption: Assumption, onSettle: ((String, String) -> Unit)?) {
    var changing by rememberSaveable(assumption) { mutableStateOf(false) }
    var enteringAnswer by rememberSaveable(assumption) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun cancel() {
        focus.clearFocus()
        keyboard?.hide()
        changing = false
        enteringAnswer = false
    }
    fun submit(answer: String) {
        cancel()
        onSettle?.invoke(assumption.about, answer)
    }
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
            if (onSettle != null && !changing) {
                val clarify = stringResource(R.string.clarify_about, assumption.about)
                TextButton(
                    onClick = {
                        changing = true
                        enteringAnswer = assumption.alternatives.none { it.isNotBlank() }
                    },
                    modifier = Modifier.semantics { contentDescription = clarify },
                ) { Text(stringResource(R.string.clarify)) }
            }
        }
        if (onSettle != null && changing) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.what_did_you_mean), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assumptionChoices(assumption).forEach { alternative ->
                    SuggestionChip(onClick = { submit(alternative) }, label = { Text(alternative) })
                }
            }
            if (enteringAnswer) {
                ClarificationEditor(initialAnswer = "", onSubmit = ::submit, onCancel = ::cancel)
            } else {
                Row {
                    TextButton(onClick = { enteringAnswer = true }) { Text(stringResource(R.string.something_else)) }
                    TextButton(onClick = ::cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/** An unsent draft belongs to this editor; only the explicit submission changes a check. */
@Composable
private fun ClarificationEditor(initialAnswer: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var answer by rememberSaveable(initialAnswer) { mutableStateOf(initialAnswer) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = answer.trim()
    val tooLong = trimmed.length > Checker.MAX_CHARS
    fun finish(action: () -> Unit) {
        focus.clearFocus()
        keyboard?.hide()
        action()
    }
    // The editor enters composition only after the writer explicitly opens it.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = answer,
        onValueChange = { answer = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(stringResource(R.string.what_i_meant)) },
        minLines = 2,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        isError = tooLong,
        supportingText = if (tooLong) {
            { Text(stringResource(R.string.clarification_too_long, Checker.MAX_CHARS)) }
        } else null,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            enabled = trimmed.isNotEmpty() && !tooLong,
            onClick = { if (trimmed.isNotEmpty() && !tooLong) finish { onSubmit(trimmed) } },
        ) { Text(stringResource(R.string.check_again)) }
        TextButton(onClick = { finish(onCancel) }) { Text(stringResource(R.string.cancel)) }
    }
}

/** Include the current reading so clarification can confirm it as well as correct it. */
internal fun assumptionChoices(assumption: Assumption): List<String> =
    (listOf(assumption.assumed) + assumption.alternatives).filter { it.isNotBlank() }.distinct()

internal fun linkedAssumptions(indices: List<Int>, assumptions: List<Assumption>, settled: List<Settled>): List<Assumption> =
    indices.distinct().mapNotNull(assumptions::getOrNull).filter { a ->
        hasUniqueLabel(a, assumptions) && settled.none { it.answers(a.about) }
    }

/** Settling identifies questions by their label; never offer an action that could answer two. */
private fun hasUniqueLabel(assumption: Assumption, assumptions: List<Assumption>) =
    assumptions.count { it.about == assumption.about } == 1

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
