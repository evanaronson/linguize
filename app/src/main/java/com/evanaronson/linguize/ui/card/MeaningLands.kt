package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.ui.components.Disclosure
import com.evanaronson.linguize.ui.components.DisclosureMotion
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.tonecheck.LandingDetails
import com.evanaronson.linguize.ui.tonecheck.overallTitle

/*
 * Lab, Meaning (docs/lab/meaning-lands.md): one section for "does it say what I mean, and how
 * will it come across?". The exact meaning and its connotation feed each other, so they are
 * one Disclosure at the foot of the card, not a Meaning page plus a How it lands row.
 *
 * Collapsed: Meaning, the meaning in up to two lines, "Lands well · friendly", and how many
 * readings are still assumed. Expanded, top to bottom: the full meaning, the writer's
 * clarification (or "Not what you mean"), the assumptions it rests on, then how it lands.
 */

/** The parts of the open section, in the order they are drawn and read. */
internal enum class MeaningPart { Meaning, Clarification, Assumptions, Landing }

/** What a check says about the text's meaning and its landing, sorted for the section. */
internal data class MeaningOutline(
    val meaning: String,
    val assumptions: List<Assumption>,
    /** The writer's clarification of the whole message, if any. */
    val clarification: Settled?,
    /** The writer's answers to assumptions. */
    val answers: List<Settled>,
    /** Assumptions nobody has answered: the suggestions still rest on them. */
    val open: List<Assumption>,
    val landing: Landing?,
) {
    /** The open section's parts, in order; [canClarify] is false on a past check. */
    fun parts(canClarify: Boolean): List<MeaningPart> = buildList {
        if (meaning.isNotBlank()) add(MeaningPart.Meaning)
        if (clarification != null || canClarify) add(MeaningPart.Clarification)
        if (assumptions.isNotEmpty() || answers.isNotEmpty()) add(MeaningPart.Assumptions)
        if (landing != null) add(MeaningPart.Landing)
    }
}

internal fun meaningOutline(meaning: String, assumptions: List<Assumption>, settled: List<Settled>, landing: Landing?): MeaningOutline {
    val answers = settled.filterNot { it.isMeaning }
    return MeaningOutline(
        meaning = meaning.trim(),
        assumptions = assumptions,
        clarification = settled.firstOrNull { it.isMeaning },
        answers = answers,
        open = assumptions.filterNot { a -> answers.any { it.answers(a.about) } },
        landing = landing,
    )
}

/**
 * The check card's Meaning: edge to edge under a hairline at the card's foot, below the
 * suggestions and their final Replace or Send, so it never moves that row. [expanded] is kept
 * by the card, so a clarification sent from inside reopens on the new answer.
 */
@Composable
internal fun MeaningFooter(
    result: CheckResult.Reviewed,
    settled: List<Settled>,
    actions: CardActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val outline = meaningOutline(result.meaning, result.assumptions, settled, result.landing)
    if (outline.parts(canClarify = actions.onClarify != null).isEmpty()) return
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MeaningLandsSection(
            outline, expanded, onExpandedChange,
            onSettle = actions.onSettle,
            onRemoveChoice = actions.onRemoveChoice,
            onClarify = actions.onClarify,
            onRemoveClarification = actions.onRemoveClarification,
            headerPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp),
        )
    }
}

/**
 * The one Meaning section, live on the card or read-only on a saved check (all actions null).
 * Draws nothing when there is nothing to show or do.
 */
@Composable
internal fun MeaningLandsSection(
    outline: MeaningOutline,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    /** Null on a past check, which cannot be checked again. */
    onSettle: ((String, String) -> Unit)?,
    /** Null on a past check, whose answers remain evidence of that request. */
    onRemoveChoice: ((String) -> Unit)?,
    onClarify: ((String) -> Unit)?,
    onRemoveClarification: (() -> Unit)?,
    modifier: Modifier = Modifier,
    headerPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
) {
    val parts = outline.parts(canClarify = onClarify != null)
    if (parts.isEmpty()) return
    Disclosure(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        header = { MeaningHeader(outline, expanded) },
        modifier = modifier,
        expandLabel = stringResource(R.string.lab_meaning_lands_show),
        collapseLabel = stringResource(R.string.lab_meaning_lands_hide),
        headerPadding = headerPadding,
    ) {
        Column(Modifier.padding(contentPadding)) {
            parts.forEachIndexed { index, part ->
                if (index > 0) Spacer(Modifier.height(if (part == MeaningPart.Clarification) 8.dp else 18.dp))
                when (part) {
                    MeaningPart.Meaning -> TextBlock(outline.meaning)
                    MeaningPart.Clarification -> key(outline.meaning) {
                        Clarification(outline.clarification, onClarify, onRemoveClarification)
                    }
                    MeaningPart.Assumptions -> Assumptions(outline.assumptions, outline.answers, onSettle, onRemoveChoice)
                    MeaningPart.Landing -> outline.landing?.let { LandingPart(it) }
                }
            }
        }
    }
}

/**
 * "Meaning" over its summary. The summary is a glance at what opens below, so it gives way to
 * the full content when the section is open; the title stays put.
 */
@Composable
private fun MeaningHeader(outline: MeaningOutline, expanded: Boolean) {
    Column {
        Text(stringResource(R.string.meaning), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() })
        AnimatedVisibility(
            visible = !expanded,
            enter = expandVertically(tween(DisclosureMotion.COLLAPSE_MILLIS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(DisclosureMotion.COLLAPSE_MILLIS)),
            exit = shrinkVertically(tween(DisclosureMotion.EXPAND_MILLIS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(DisclosureMotion.EXPAND_MILLIS)),
        ) {
            Column {
                if (outline.meaning.isNotBlank()) SummaryLine(outline.meaning, maxLines = 2)
                outline.landing?.let { SummaryLine(landingLine(it)) }
                if (outline.open.isNotEmpty()) {
                    SummaryLine(pluralStringResource(R.plurals.lab_meaning_lands_assumptions, outline.open.size, outline.open.size))
                }
                // Nothing to summarise (a text already in your language, no readings): say what opens.
                if (outline.meaning.isBlank() && outline.landing == null && outline.open.isEmpty()) {
                    SummaryLine(stringResource(R.string.meaning_subtitle))
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(text: String, maxLines: Int = 1) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp))
}

/** "Lands well · friendly, a bit direct". */
@Composable
private fun landingLine(landing: Landing): String =
    stringResource(R.string.lab_tone_check_row_subtitle, stringResource(overallTitle(landing.overall)), landing.summary)

/** How it lands, as the last part: its reading in one line, then the words, scales and notes behind it. */
@Composable
private fun LandingPart(landing: Landing) {
    Column {
        Text(stringResource(R.string.lab_tone_check_title), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() })
        Text(landingLine(landing), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        LandingDetails(landing)
    }
}
