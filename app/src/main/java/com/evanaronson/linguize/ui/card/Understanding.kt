package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.ClarifyLink
import com.evanaronson.linguize.ui.components.FooterRow
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.TextBlock

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

/** The way into Meaning: the card's last row, edge to edge. */
@Composable
internal fun UnderstandingEntry(entry: UnderstandingEntryState, onOpen: () -> Unit) {
    if (!entry.visible) return
    FooterRow(
        stringResource(R.string.meaning),
        stringResource(R.string.meaning_subtitle),
        onClick = onOpen,
        onClickLabel = stringResource(R.string.show_understanding),
    )
}

/**
 * How the text was read: the meaning, the writer's clarification, and the assumptions the
 * suggestions rest on. Live checks can always be clarified, even without a supplied meaning.
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
    PageHeader(stringResource(R.string.meaning)) {
        focus.clearFocus()
        keyboard?.hide()
        onBack()
    }
    MeaningSection(meaning, settled, onClarify, onRemoveClarification)
    val assumptionAnswers = settled.filterNot { it.isMeaning }
    if (assumptions.isNotEmpty() || assumptionAnswers.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Assumptions(assumptions, assumptionAnswers, onSettle, onRemoveChoice)
    }
}

/** Back arrow and title, inside a card that has a page of its own. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.offset(x = (-12).dp).padding(bottom = 2.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.offset(y = (-6).dp)) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
        }
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.offset(y = (-6).dp).semantics { heading() })
    }
}

/**
 * Shared by Meaning and an unclear result, so either can take the writer's intent: the
 * reading, then "Not what you mean ›" (or [linkText]) opening the [ClarifyBlock] for the
 * whole message, and the clarification once given.
 */
@Composable
internal fun MeaningSection(
    meaning: String,
    settled: List<Settled>,
    onClarify: ((String) -> Unit)?,
    onRemoveClarification: (() -> Unit)?,
    linkText: String = stringResource(R.string.not_what_you_mean),
    /** An unclear result titles the editor, since there is no meaning above it. */
    editorTitle: String? = null,
) {
    val clarification = settled.firstOrNull { it.isMeaning }
    if (meaning.isBlank() && clarification == null && onClarify == null) return
    var changing by rememberSaveable(meaning, clarification?.answer) { mutableStateOf(false) }
    if (meaning.isNotBlank()) {
        Text(
            stringResource(R.string.meaning_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextBlock(meaning)
    }
    if (onClarify != null && changing) {
        if (editorTitle != null) {
            Text(editorTitle, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        ClarifyBlock(
            choices = emptyList(),
            initialAnswer = clarification?.answer.orEmpty(),
            onSubmit = { answer ->
                changing = false
                onClarify(answer)
            },
            onCancel = { changing = false },
        )
        return
    }
    if (onClarify != null) {
        if (meaning.isNotBlank()) Spacer(Modifier.height(8.dp))
        val label = stringResource(R.string.clarify_meaning)
        ClarifyLink(linkText, onClick = { changing = true },
            modifier = Modifier.semantics { contentDescription = label })
    }
    if (clarification != null) {
        Spacer(Modifier.height(12.dp))
        Reading(stringResource(R.string.your_clarification), clarification.answer)
        if (onRemoveClarification != null) {
            LinguizeButton(stringResource(R.string.remove_clarification), onRemoveClarification, ButtonKind.Text)
        }
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
        stringResource(if (onSettle != null) R.string.assumptions_description else R.string.assumptions_description_past),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    AssumptionList(assumptions, open, settled, onSettle, onRemoveChoice)
}

/** Answered readings first, then open ones, each on its own inset. */
@Composable
internal fun AssumptionList(
    assumptions: List<Assumption>,
    open: List<Assumption>,
    settled: List<Settled>,
    onSettle: ((String, String) -> Unit)?,
    onRemoveChoice: ((String) -> Unit)?,
) {
    Column {
        settled.forEach { answer -> AnsweredInset(answer, onRemoveChoice) }
        open.forEach { assumption ->
            AssumptionInset(assumption, onSettle.takeIf { hasUniqueLabel(assumption, assumptions) })
        }
    }
}

/** A reading the writer chose, removable while the check is live. */
@Composable
private fun AnsweredInset(answer: Settled, onRemoveChoice: ((String) -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(InsetShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) { Reading(answer.about, answer.answer) }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.your_choice), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRemoveChoice != null) {
                val remove = stringResource(R.string.remove_choice_about, answer.about)
                LinguizeButton(
                    stringResource(R.string.remove_choice), { onRemoveChoice(answer.about) }, ButtonKind.Text,
                    modifier = Modifier.semantics { contentDescription = remove },
                )
            }
        }
    }
}

internal fun linkedAssumptions(indices: List<Int>, assumptions: List<Assumption>, settled: List<Settled>): List<Assumption> =
    indices.distinct().mapNotNull(assumptions::getOrNull).filter { a ->
        hasUniqueLabel(a, assumptions) && settled.none { it.answers(a.about) }
    }

/** The writer's answers to the readings a change rests on, for "You said …" under it. */
internal fun linkedAnswers(indices: List<Int>, assumptions: List<Assumption>, settled: List<Settled>): List<String> =
    indices.distinct().mapNotNull(assumptions::getOrNull).mapNotNull { a ->
        settled.lastOrNull { it.answers(a.about) }?.answer
    }

/** Settling identifies questions by their label; never offer an action that could answer two. */
internal fun hasUniqueLabel(assumption: Assumption, assumptions: List<Assumption>) =
    assumptions.count { it.about == assumption.about } == 1

