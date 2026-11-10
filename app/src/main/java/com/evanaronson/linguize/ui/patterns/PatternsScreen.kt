package com.evanaronson.linguize.ui.patterns

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.SavedExplanation
import com.evanaronson.linguize.ExplanationStatus
import com.evanaronson.linguize.history.patterns.MAX_CHECKS
import com.evanaronson.linguize.history.patterns.Pattern
import com.evanaronson.linguize.history.patterns.PatternAnalyser
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.history.patterns.PatternReport
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.text.labelResource
import com.evanaronson.linguize.ui.text.localizedText
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.progress.EmptyCard
import com.evanaronson.linguize.ui.progress.HowItsCounted
import com.evanaronson.linguize.ui.progress.PageLoading
import com.evanaronson.linguize.ui.progress.ScopeHeader
import com.evanaronson.linguize.ui.progress.StatRow
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Your patterns: recurring groups of fixes from saved checks, an optional explanation, and
 * how the counting works. Tap a group for its examples; tap an example for its saved check.
 */
@Composable
fun PatternsScreen(
    patterns: PatternsViewModel,
    historyOn: Boolean?,
    onOpenPattern: (PatternKind) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by patterns.state.collectAsState()
    LaunchedEffect(patterns) { patterns.refresh() }
    val report = state.snapshot?.report
    ScreenColumn {
        when {
            report == null && state.loading -> Loading()
            report == null -> Failed(patterns::refresh)
            else -> {
                // Lab, Progress IA: the language, then a summary, as on every page Progress opens.
                ScopeHeader(state.language)
                if (report.patterns.isEmpty()) {
                    EmptyCard(stringResource(R.string.lab_patterns_empty_title), stringResource(R.string.lab_patterns_empty))
                } else {
                    Summary(report)
                    SectionHeader(stringResource(R.string.lab_patterns_recurring))
                    SectionCard {
                        report.patterns.forEachIndexed { index, pattern ->
                            if (index > 0) RowDivider()
                            PatternRow(pattern, onClick = { onOpenPattern(pattern.kind) })
                        }
                    }
                    CardNote(stringResource(R.string.lab_patterns_trend_caveat))
                    SectionHeader(stringResource(R.string.lab_patterns_explain_header))
                    ExplainCard(state, patterns::explain, patterns::deleteExplanation, onOpenSettings)
                }
                if (report.unpatterned.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.lab_patterns_not_grouped))
                    SectionCard {
                        ListRow(
                            pluralStringResource(R.plurals.lab_patterns_other_fixes, report.unpatterned.size, report.unpatterned.size),
                            stringResource(R.string.lab_patterns_not_grouped_summary),
                            onClick = { onOpenPattern(PatternKind.Other) },
                            onClickLabel = stringResource(R.string.lab_patterns_open_pattern),
                        )
                    }
                }
                if (state.failed) Failed(patterns::refresh)
                HowItsCounted(
                    listOf(
                        stringResource(R.string.lab_patterns_how_counting),
                        stringResource(R.string.lab_patterns_how_deleting),
                        stringResource(R.string.lab_patterns_how_version, PatternAnalyser.VERSION, MAX_CHECKS),
                    ),
                )
            }
        }
        if (historyOn == false) CardNote(stringResource(R.string.history_off))
    }
}

private val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

/** How many groups, from how many checks, since when; and whether Linguize guessed any. */
@Composable
private fun Summary(report: PatternReport) {
    val resources = LocalResources.current
    SectionCard(contentPadding = padding) {
        StatRow(
            report.patterns.size.toString() to stringResource(R.string.lab_ia_progress_stat_patterns),
            report.checksCounted.toString() to stringResource(R.string.lab_ia_progress_stat_checks),
        )
        report.firstAt?.let { Quiet(stringResource(R.string.lab_ia_progress_since, shortDate(it, locale = resources.uiLocale)), Modifier.padding(top = 12.dp)) }
    }
    if (report.patterns.any { it.inferred }) CardNote(stringResource(R.string.lab_patterns_inferred_note))
}

@Composable
internal fun countLine(pattern: Pattern): String =
    pluralStringResource(R.plurals.lab_patterns_times_in_checks, pattern.checks, pattern.occurrences, pattern.checks)

@Composable
private fun PatternRow(pattern: Pattern, onClick: () -> Unit) {
    ListRow(
        stringResource(pattern.kind.labelResource),
        stringResource(R.string.lab_patterns_row_summary, countLine(pattern), stringResource(pattern.trend.labelResource)),
        onClick = onClick,
        onClickLabel = stringResource(R.string.lab_patterns_open_pattern),
    ) {
        Sparkline(pattern.buckets, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { })
    }
}

/** Small bars, one per equal span of time; decorative where the numbers are also in words. */
@Composable
internal fun Sparkline(values: List<Int>, modifier: Modifier = Modifier, barGap: Dp = 3.dp) {
    val bar = Linguize.colors.proof
    val empty = Linguize.colors.rowDivider
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1)
        val gap = barGap.toPx()
        val width = (size.width - gap * (values.size - 1)) / values.size
        val radius = CornerRadius(width / 4, width / 4)
        values.forEachIndexed { index, value ->
            val left = index * (width + gap)
            val height = if (value == 0) 2.dp.toPx() else size.height * value / max
            drawRoundRect(
                color = if (value == 0) empty else bar,
                topLeft = Offset(left, size.height - height),
                size = Size(width, height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun ExplainCard(
    state: PatternsState,
    onExplain: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val snapshot = state.snapshot ?: return
    val explanation = snapshot.explanation
    SectionCard(contentPadding = padding) {
        if (explanation != null) {
            Explanation(explanation, snapshot.status)
            Spacer(Modifier.height(8.dp))
        }
        state.explainFailure?.let { failure ->
            Text(stringResource(failure.reason.titleResource), style = MaterialTheme.typography.titleSmall)
            failure.message?.let { Quiet(it.localizedText()) }
            Spacer(Modifier.height(8.dp))
        }
        when {
            state.explaining -> {
                val label = stringResource(R.string.lab_patterns_explaining)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics { }, strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Quiet(label)
                }
            }
            !state.canExplain -> {
                Quiet(stringResource(R.string.lab_patterns_explain_no_key))
                LinguizeButton(stringResource(R.string.open_settings), onOpenSettings, ButtonKind.Text)
            }
            else -> {
                if (explanation == null) {
                    Quiet(stringResource(R.string.lab_patterns_explain_sends, providerName(state.provider)))
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinguizeButton(
                        stringResource(if (explanation == null) R.string.lab_patterns_explain else R.string.lab_patterns_explain_again),
                        onExplain,
                        if (explanation == null) ButtonKind.Tonal else ButtonKind.Text,
                        enabled = !state.loading && !state.failed,
                    )
                    if (explanation != null) {
                        LinguizeButton(stringResource(R.string.lab_patterns_delete_explanation), onDelete, ButtonKind.Destructive)
                    }
                }
            }
        }
    }
}

@Composable
private fun providerName(provider: Provider?): String =
    provider?.let { stringResource(it.labelResource) } ?: stringResource(R.string.lab_patterns_title)

@Composable
private fun Explanation(explanation: SavedExplanation, status: ExplanationStatus?) {
    val resources = LocalResources.current
    Text(explanation.summary, style = MaterialTheme.typography.bodyLarge)
    explanation.tips.forEach { tip ->
        val kind = PatternKind.read(tip.pattern) ?: return@forEach
        Spacer(Modifier.height(12.dp))
        Text(stringResource(kind.labelResource), style = MaterialTheme.typography.titleSmall)
        Text(tip.tip, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(12.dp))
    val by = Provider.tokens.decode(explanation.provider)?.let { stringResource(it.labelResource) } ?: explanation.provider
    Quiet(
        pluralStringResource(
            R.plurals.lab_patterns_explained_by, explanation.checks,
            by, shortDate(explanation.createdAt, locale = resources.uiLocale), explanation.checks,
        ),
    )
    if (status == ExplanationStatus.Outdated) Quiet(stringResource(R.string.lab_patterns_explained_outdated))
}

@Composable
internal fun Loading() {
    PageLoading(stringResource(R.string.lab_patterns_loading))
}

@Composable
internal fun Failed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 20.dp)) {
        Quiet(stringResource(R.string.history_load_failed))
        LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Text)
    }
}
