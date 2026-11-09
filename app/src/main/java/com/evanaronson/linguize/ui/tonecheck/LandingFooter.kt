package com.evanaronson.linguize.ui.tonecheck

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingOverall
import com.evanaronson.linguize.ui.components.Chevron
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Lab, How it lands: the check card's footer row under Meaning, from the same answer as the
 * fixes. It reads "Lands well · friendly, a bit direct"; tapping it opens the details in
 * place (the judged text with the words that shape it highlighted, three scales, notes) and
 * tapping again closes them. No page, no request of its own.
 */
@Composable
fun LandingFooter(landing: Landing) {
    var expanded by rememberSaveable(landing) { mutableStateOf(false) }
    val title = stringResource(R.string.lab_tone_check_title)
    val subtitle = stringResource(R.string.lab_tone_check_row_subtitle, stringResource(overallTitle(landing.overall)), landing.summary)
    val toggleLabel = stringResource(if (expanded) R.string.lab_tone_check_hide else R.string.lab_tone_check_show)
    val state = stringResource(if (expanded) R.string.lab_tone_check_expanded else R.string.lab_tone_check_collapsed)
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
            // The end-pointing chevron turned to point down (closed) or up (open), in either direction.
            val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
            Chevron(Modifier.rotate(turn * if (expanded) -90f else 90f))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LandingDetails(landing, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp))
        }
    }
}

/** The details: what it was read as, the scales and the notes. Read-only. */
@Composable
internal fun LandingDetails(landing: Landing, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (landing.drivers.isNotEmpty()) {
            val highlight = Linguize.colors.rewording.copy(alpha = 0.22f)
            val marked = remember(landing, highlight) { marked(landing.text, landing.drivers, highlight) }
            val description = stringResource(R.string.lab_tone_check_drivers_description,
                landing.drivers.joinToString(", ") { "“$it”" })
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Linguize.colors.panel)
                    .padding(12.dp),
            ) {
                Text(marked, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                stringResource(R.string.lab_tone_check_drivers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { contentDescription = description },
            )
            Spacer(Modifier.height(14.dp))
        }
        Scale(R.string.lab_tone_check_warmth, landing.warmth, R.string.lab_tone_check_cold, R.string.lab_tone_check_warm)
        Spacer(Modifier.height(10.dp))
        Scale(R.string.lab_tone_check_directness, landing.directness, R.string.lab_tone_check_indirect, R.string.lab_tone_check_blunt)
        Spacer(Modifier.height(10.dp))
        Scale(R.string.lab_tone_check_formality, landing.formality, R.string.lab_tone_check_casual, R.string.lab_tone_check_formal)
        if (landing.notes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.lab_tone_check_notes), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            landing.notes.forEach { note ->
                Spacer(Modifier.height(4.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.lab_tone_check_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One reading on a five-step scale, spoken as a single phrase. */
@Composable
private fun Scale(label: Int, value: Int, low: Int, high: Int) {
    val name = stringResource(label)
    val lowText = stringResource(low)
    val highText = stringResource(high)
    val description = stringResource(R.string.lab_tone_check_scale_description, name, value, Landing.SCALE_MAX, lowText, highText)
    val filled = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (step in Landing.SCALE_MIN..Landing.SCALE_MAX) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (step <= value) filled else track),
                    )
                }
            }
        }
        Row(Modifier.padding(start = 96.dp, top = 2.dp)) {
            Text(lowText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(highText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [text] with every occurrence of each driver marked; overlapping marks simply merge. */
internal fun marked(text: String, drivers: List<String>, highlight: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in driverRanges(text, drivers)) {
        addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Medium), range.first, range.last + 1)
    }
}

/** Where each driver occurs in [text]. */
internal fun driverRanges(text: String, drivers: List<String>): List<IntRange> = drivers.filter { it.isNotEmpty() }.flatMap { quote ->
    generateSequence(text.indexOf(quote).takeIf { it >= 0 }) { from ->
        text.indexOf(quote, from + quote.length).takeIf { it >= 0 }
    }.map { it until it + quote.length }.toList()
}.sortedBy { it.first }

internal fun overallTitle(overall: LandingOverall) = when (overall) {
    LandingOverall.LandsWell -> R.string.lab_tone_check_lands_well
    LandingOverall.CouldLandDifferently -> R.string.lab_tone_check_could_land_differently
    LandingOverall.CouldLandBadly -> R.string.lab_tone_check_could_land_badly
}
