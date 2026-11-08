package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.ui.teachme.TeachMe
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.ClarifyLink
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.hearit.HearItChange
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Why one change, in the order a writer weighs it: the change and its reason, then how
 * much it matters (two three-step meters, impact first), then, under a hairline, the
 * reading it rests on. That reading is a [ClarifyLink] that opens the [ClarifyBlock] in
 * place; while it is open, Accept steps back to outlined so Clarify takes precedence.
 * A reading the writer already answered is shown as theirs.
 *
 * Used live (with [onAccept]) and read-only for a saved check.
 */
@Composable
internal fun ExplanationPanel(
    from: String,
    to: String,
    /** Null when the change is already shown above the panel. */
    description: String?,
    why: String?,
    assessment: SuggestionAssessment?,
    assumptions: List<Assumption>,
    answers: List<String>,
    accent: Color,
    modifier: Modifier = Modifier,
    onAccept: (() -> Unit)? = null,
    onSettle: ((String, String) -> Unit)? = null,
    /** For spacing changes: the words around an otherwise invisible change. */
    context: String? = null,
    /** Collapsed spacing panels show only the change until opened. */
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onToggleLabel: String? = null,
    /** Lab: the decision "Teach me" explains; null offers no lesson. */
    lesson: LessonTopic? = null,
) {
    var clarifying by rememberSaveable(assumptions) { mutableStateOf<String?>(null) }
    val edge = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(edge)
            .background(Linguize.colors.panel)
            .drawBehind {
                val width = 2.dp.toPx()
                drawLine(accent, Offset(width / 2, 0f), Offset(width / 2, size.height), width)
            }
            .then(if (onToggle != null) Modifier.clickable(onClickLabel = onToggleLabel, onClick = onToggle) else Modifier)
            // Read out as it opens, which matters when a screen reader's "Why" action opened it.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        description?.let { ChangeLine(from, to, it) }
        if (!context.isNullOrBlank()) {
            Text(context, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            why?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (description != null) 2.dp else 0.dp))
            }
        }
        if (to.isNotBlank()) HearItChange(to)
        onAccept?.let {
            LinguizeButton(
                stringResource(R.string.accept), onClick = it,
                kind = if (clarifying != null) ButtonKind.Outlined else ButtonKind.Tonal,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            )
        }
        if (expanded) WeightMeters(assessment, Modifier.padding(top = 8.dp))
        if (expanded) TeachMe(lesson)
        if (expanded && (assumptions.isNotEmpty() || answers.isNotEmpty())) {
            HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(top = 4.dp)) {
                assumptions.forEach { assumption ->
                    val reading = stringResource(R.string.assumes, assumption.assumed)
                    when {
                        onSettle != null && clarifying == assumption.about -> ClarifyBlock(
                            choices = assumptionChoices(assumption),
                            onSubmit = { answer ->
                                clarifying = null
                                onSettle(assumption.about, answer)
                            },
                            onCancel = { clarifying = null },
                            about = assumption.about,
                            submitKind = ButtonKind.Tonal,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        onSettle != null -> {
                            val label = stringResource(R.string.clarify_about, assumption.about)
                            ClarifyLink(reading, onClick = { clarifying = assumption.about },
                                onClickLabel = label)
                        }
                        else -> Footnote(reading)
                    }
                }
                answers.forEach { Footnote(stringResource(R.string.you_said, it)) }
            }
        }
    }
}

/** "vaig poder → podré", with the old words struck through; spoken in words. */
@Composable
private fun ChangeLine(from: String, to: String, description: String) {
    val display = if (from.isEmpty() || to.isEmpty()) description
        else stringResource(R.string.change_replacement, from, to)
    Text(
        buildAnnotatedString {
            append(display)
            if (from.isNotEmpty() && to.isNotEmpty()) {
                val fromStart = display.indexOf(from)
                if (fromStart >= 0) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), fromStart, fromStart + from.length)
                val toStart = display.lastIndexOf(to)
                if (toStart >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), toStart, toStart + to.length)
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        // In words: the arrow would be read out as "right arrow".
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * Impact, then confidence, each as three bars: weight without words. Screen readers hear
 * the scores in words. Nothing is drawn for a change that was never assessed.
 */
@Composable
internal fun WeightMeters(assessment: SuggestionAssessment?, modifier: Modifier = Modifier) {
    if (assessment == null) return
    val presentation = assessmentPresentation(assessment)
    val spoken = listOf(
        stringResource(R.string.assessment_dimension, stringResource(R.string.assessment_impact), stringResource(presentation.impact.label)),
        stringResource(R.string.assessment_dimension, stringResource(R.string.assessment_confidence), stringResource(presentation.confidence.label)),
    ).joinToString(stringResource(R.string.list_separator))
    FlowRow(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Meter(stringResource(R.string.assessment_impact), checkNotNull(presentation.impact.level))
        Meter(stringResource(R.string.assessment_confidence), checkNotNull(presentation.confidence.level))
    }
}

@Composable
private fun Meter(label: String, level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { step ->
                Box(
                    Modifier
                        .size(width = 10.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (step < level) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}
