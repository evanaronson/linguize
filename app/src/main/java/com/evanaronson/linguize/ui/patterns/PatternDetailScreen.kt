package com.evanaronson.linguize.ui.patterns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.patterns.PairCount
import com.evanaronson.linguize.history.patterns.Pattern
import com.evanaronson.linguize.history.patterns.PatternExample
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.history.patterns.PatternReport
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale

/** One group: how often, over time, how it was grouped, and every example with its saved check. */
@Composable
fun PatternDetailScreen(patterns: PatternsViewModel, kind: PatternKind, onOpenCheck: (String) -> Unit) {
    val state by patterns.state.collectAsState()
    LaunchedEffect(patterns) { patterns.refresh() }
    val report = state.snapshot?.report
    ScreenColumn {
        when {
            report == null && state.loading -> Loading()
            report == null -> Failed(patterns::refresh)
            kind == PatternKind.Other -> Ungrouped(report, onOpenCheck)
            else -> {
                val pattern = report.pattern(kind)
                if (pattern == null) {
                    CardNote(stringResource(R.string.lab_patterns_missing))
                } else {
                    Summary(pattern, report)
                    if (pattern.pairs.any { it.count > 1 }) MostCommon(pattern.pairs.filter { it.count > 1 })
                    SectionHeader(stringResource(R.string.lab_patterns_examples))
                    Examples(pattern.examples, showBasis = pattern.bases.size > 1, onOpenCheck)
                }
            }
        }
    }
}

private val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
private fun Summary(pattern: Pattern, report: PatternReport) {
    val resources = LocalResources.current
    Spacer(Modifier.height(8.dp))
    SectionCard(contentPadding = padding) {
        Text(countLine(pattern), style = MaterialTheme.typography.titleMedium)
        Quiet(stringResource(pattern.kind.aboutResource))
        val first = report.firstAt
        val last = report.lastAt
        if (first != null && last != null && last > first) {
            val from = shortDate(first, locale = resources.uiLocale)
            val to = shortDate(last, locale = resources.uiLocale)
            val description = stringResource(R.string.lab_patterns_chart_description, from, to, pattern.buckets.joinToString(", "))
            Spacer(Modifier.height(16.dp))
            Sparkline(
                pattern.buckets,
                Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description },
                barGap = 6.dp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
                Text(from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text(to, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(pattern.trend.labelResource).replaceFirstChar { it.uppercase(resources.uiLocale) },
            style = MaterialTheme.typography.bodyMedium)
        Quiet(stringResource(if (pattern.inferred) R.string.lab_patterns_grouped_inferred else R.string.lab_patterns_grouped_exact))
    }
    CardNote(stringResource(R.string.lab_patterns_trend_caveat))
}

@Composable
private fun MostCommon(pairs: List<PairCount>) {
    SectionHeader(stringResource(R.string.lab_patterns_most_common))
    SectionCard {
        pairs.take(5).forEachIndexed { index, pair ->
            if (index > 0) RowDivider()
            val spoken = stringResource(R.string.lab_patterns_change_spoken, pair.from.orDeleted(), pair.to.orDeleted())
            val times = pluralStringResource(R.plurals.lab_patterns_times, pair.count, pair.count)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp)
                    .clearAndSetSemantics { contentDescription = "$spoken, $times" },
            ) {
                Text(stringResource(R.string.lab_patterns_change, pair.from.orDeleted(), pair.to.orDeleted()),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(times, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun String.orDeleted(): String = ifBlank { stringResource(R.string.lab_patterns_deleted_text) }

@Composable
private fun Examples(examples: List<PatternExample>, showBasis: Boolean, onOpenCheck: (String) -> Unit, showKind: Boolean = false) {
    val resources = LocalResources.current
    val openLabel = stringResource(R.string.open_check)
    SectionCard {
        examples.forEachIndexed { index, example ->
            if (index > 0) RowDivider()
            val change = stringResource(R.string.lab_patterns_change, example.from.trim().orDeleted(), example.to.trim().orDeleted())
            val spoken = stringResource(R.string.lab_patterns_change_spoken, example.from.trim().orDeleted(), example.to.trim().orDeleted())
            val date = shortDate(example.at, locale = resources.uiLocale)
            val basis = if (showBasis) stringResource(example.basis.labelResource) else null
            val group = if (showKind && example.kind != PatternKind.Other) stringResource(example.kind.labelResource) else null
            Column(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { onOpenCheck(example.sessionId) }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp)
                    .semantics(mergeDescendants = true) { },
            ) {
                val from = stringResource(com.evanaronson.linguize.ui.chat.originLabel(example.origin))
                Text(listOfNotNull(date, from, group, basis).joinToString(stringResource(R.string.status_separator)),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(change, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { contentDescription = spoken })
                example.why?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Ungrouped(report: PatternReport, onOpenCheck: (String) -> Unit) {
    CardNote(stringResource(PatternKind.Other.aboutResource))
    Spacer(Modifier.height(4.dp))
    if (report.unpatterned.isEmpty()) return
    SectionHeader(stringResource(R.string.lab_patterns_examples))
    Examples(report.unpatterned, showBasis = false, onOpenCheck, showKind = true)
}
