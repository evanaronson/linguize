package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.evanaronson.linguize.ui.components.Chevron
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.theme.Linguize

/** Back arrow and title, inside a card that has a page of its own (the clipboard job's). */
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
 * An unclear result's way to take the writer's intent: the reading, then [linkText] before a
 * clarification exists. A reviewed check has the Meaning section instead ([MeaningLandsSection]). The saved clarification itself
 * opens the same prefilled [ClarifyBlock] when it can still be edited.
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
    submitKind: ButtonKind = ButtonKind.Filled,
) {
    val clarification = settled.firstOrNull { it.isMeaning }
    if (meaning.isBlank() && clarification == null && onClarify == null) return
    if (meaning.isNotBlank()) {
        Text(
            stringResource(R.string.meaning_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextBlock(meaning)
        if (clarification != null || onClarify != null) Spacer(Modifier.height(8.dp))
    }
    key(meaning) {
        Clarification(clarification, onClarify, onRemoveClarification, linkText, editorTitle, submitKind)
    }
}

/** One entry, saved row, and editor for a clarification of either scope. */
@Composable
internal fun Clarification(
    clarification: Settled?,
    onClarify: ((String) -> Unit)?,
    onRemove: (() -> Unit)?,
    linkText: String = stringResource(R.string.not_what_you_mean),
    editorTitle: String? = null,
    submitKind: ButtonKind = ButtonKind.Filled,
) {
    var changing by rememberSaveable(clarification) { mutableStateOf(false) }
    val about = clarification?.takeUnless { it.isMeaning }?.about
    val clarify = about?.let { stringResource(R.string.clarify_about, it) }
        ?: stringResource(R.string.clarify_meaning)
    if (onClarify != null && changing) {
        if (editorTitle != null) {
            Text(editorTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }
        ClarifyBlock(
            choices = emptyList(),
            initialAnswer = clarification?.answer.orEmpty(),
            large = about == null,
            about = about,
            submitKind = submitKind,
            onSubmit = { answer ->
                changing = false
                onClarify(answer)
            },
            onCancel = { changing = false },
        )
    } else if (clarification != null) {
        ListRow(
            stringResource(R.string.your_clarification), clarification.answer,
            onClick = onClarify?.let { { changing = true } },
            onClickLabel = clarify,
            trailing = if (onClarify != null) ({ Chevron() }) else null,
        )
        if (onRemove != null) {
            val remove = about?.let { stringResource(R.string.remove_clarification_about, it) }
                ?: stringResource(R.string.remove_clarification)
            LinguizeButton(
                onClick = onRemove, kind = ButtonKind.Destructive,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.semantics { contentDescription = remove },
            ) { Text(stringResource(R.string.remove_clarification)) }
        }
    } else if (onClarify != null) {
        ClarifyLink(linkText, onClick = { changing = true }, onClickLabel = clarify)
    }
}

/** The readings the suggestions rest on, under the Meaning section's own sub-heading. */
@Composable
internal fun Assumptions(
    assumptions: List<Assumption>,
    settled: List<Settled>,
    onSettle: ((String, String) -> Unit)?,
    onRemoveChoice: ((String) -> Unit)?,
) {
    val open = assumptions.filterNot { a -> settled.any { it.answers(a.about) } }
    Text(stringResource(R.string.assumptions), style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { heading() })
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
        settled.forEach { answer ->
            // A recheck may omit an answered assumption. Its saved answer remains editable,
            // but duplicate labels must never turn one edit into answers to two questions.
            val canEdit = assumptions.count { it.about == answer.about } <= 1 &&
                settled.count { it.answers(answer.about) } == 1
            AnsweredInset(answer, onSettle.takeIf { canEdit }, onRemoveChoice)
        }
        open.forEach { assumption ->
            AssumptionInset(assumption, onSettle.takeIf { hasUniqueLabel(assumption, assumptions) })
        }
    }
}

/** The writer's clarification keeps its topic and is editable while the check is live. */
@Composable
private fun AnsweredInset(
    answer: Settled,
    onSettle: ((String, String) -> Unit)?,
    onRemoveChoice: ((String) -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(InsetShape)
            .background(Linguize.colors.panel)
            .padding(12.dp),
    ) {
        Text(answer.about, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Clarification(
            answer,
            onClarify = onSettle?.let { settle -> { text -> settle(answer.about, text) } },
            onRemove = onRemoveChoice?.let { remove -> { remove(answer.about) } },
        )
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

