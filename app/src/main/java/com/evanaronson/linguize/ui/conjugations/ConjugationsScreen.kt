package com.evanaronson.linguize.ui.conjugations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.CellStats
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.conjugations.MonthPoint
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.progress.EmptyCard
import com.evanaronson.linguize.ui.progress.HowItsCounted
import com.evanaronson.linguize.ui.progress.PageLoading
import com.evanaronson.linguize.ui.progress.ProgressCardPadding
import com.evanaronson.linguize.ui.progress.ScopeHeader
import com.evanaronson.linguize.ui.progress.StatRow
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private const val VERBS_SHOWN = 12
private const val TRIED_SHOWN = 10
private const val TREND_MONTHS = 6

/**
 * Your conjugations: a grid of tenses and persons the writer has used, counted from their own
 * text; the verbs they use; forms per month; and forms they tried that a fix replaced.
 */
@Composable
fun ConjugationsScreen(model: ConjugationsViewModel, onOpenCell: (language: String, cell: Cell) -> Unit) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    ScreenColumn {
        // Lab, Language scope: the language is the app's, switched in the top bar.
        val report = state.report
        // Lab, Progress IA: the language, then a summary, as on every page Progress opens.
        ScopeHeader(state.shown)
        when {
            state.shown !in CONJUGATION_LANGUAGES -> EmptyCard(stringResource(R.string.lab_language_scope_conjugations_unsupported), null)
            report == null && state.loading -> PageLoading(stringResource(R.string.lab_conjugations_loading))
            report == null -> {
                CardNote(stringResource(R.string.lab_conjugations_failed))
                LinguizeButton(stringResource(R.string.lab_conjugations_retry), model::refresh, ButtonKind.Text, Modifier.padding(start = 8.dp, top = 8.dp))
            }
            report.isEmpty -> EmptyCard(stringResource(R.string.lab_ia_progress_conjugations_empty), stringResource(R.string.lab_conjugations_empty_hint))
            else -> Report(report) { onOpenCell(report.language, it) }
        }
        HowItsCounted(listOf(stringResource(R.string.lab_conjugations_how_body)))
    }
}

@Composable
private fun Report(report: ConjugationReport, onOpenCell: (Cell) -> Unit) {
    val total = report.gridCells.size
    val used = report.usedGridCells
    SectionCard(contentPadding = ProgressCardPadding) {
        StatRow(
            stringResource(R.string.lab_ia_progress_stat_of, used, total) to stringResource(R.string.lab_ia_progress_stat_forms),
            report.verbs.size.toString() to stringResource(R.string.lab_ia_progress_stat_verbs),
        )
        Quiet(stringResource(R.string.lab_conjugations_intro), Modifier.padding(top = 12.dp))
    }
    SectionHeader(stringResource(R.string.lab_conjugations_grid))
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Grid(report, onOpenCell)
    }
    CardNote(stringResource(R.string.lab_conjugations_grid_note))

    SectionHeader(stringResource(R.string.lab_conjugations_other_forms))
    SectionCard {
        ConjugationMap.NON_FINITE.forEachIndexed { index, tense ->
            if (index > 0) RowDivider()
            val cell = Cell(tense, null)
            val stats = report.stats(cell)
            ListRow(
                title = stringResource(tense.labelResource(report.language)),
                summary = usesSummary(stats),
                onClick = { onOpenCell(cell) },
                onClickLabel = stringResource(R.string.lab_conjugations_cell_examples),
            )
        }
    }

    if (report.verbs.isNotEmpty()) Verbs(report)
    if (report.trend.isNotEmpty()) Trend(report.trend.takeLast(TREND_MONTHS))
    if (report.tried.isNotEmpty()) Tried(report)
}

@Composable
private fun Grid(report: ConjugationReport, onOpenCell: (Cell) -> Unit) {
    val language = report.language
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.lab_conjugations_tense_column),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.9f).padding(start = 8.dp),
        )
        Person.entries.forEach { person ->
            Text(
                person.pronoun(language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
    report.rows.forEach { tense ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(tense.labelResource(language)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1.9f).padding(start = 8.dp, end = 4.dp).clearAndSetSemantics { },
            )
            Person.entries.forEach { person ->
                Box(Modifier.weight(1f).padding(2.dp)) {
                    if (ConjugationMap.applies(tense, person)) {
                        GridCell(language, Cell(tense, person), report.stats(Cell(tense, person)), onOpenCell)
                    } else {
                        val none = stringResource(R.string.lab_conjugations_not_a_form)
                        Box(Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = none })
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(language: String, cell: Cell, stats: CellStats?, onOpenCell: (Cell) -> Unit) {
    val locale = LocalResources.current.uiLocale
    val tense = stringResource(cell.tense.labelResource(language))
    val person = stringResource(cell.person!!.labelResource)
    val summary = usesSummary(stats)
    val description = stringResource(R.string.lab_conjugations_cell_description, tense, person, summary)
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 44.dp)
        .clip(shape)
        .clickable(onClickLabel = stringResource(R.string.lab_conjugations_cell_examples), role = Role.Button) { onOpenCell(cell) }
        .semantics { contentDescription = description }
    if (stats == null) {
        // Never used: an outlined, empty cell, so gaps stand out.
        Box(base.border(1.dp, Linguize.colors.proof.copy(alpha = 0.45f), shape), contentAlignment = Alignment.Center) {}
    } else {
        Box(base.background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(
                formatUses(stats.weight, locale),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun usesSummary(stats: CellStats?): String {
    if (stats == null) return stringResource(R.string.lab_conjugations_never_used)
    val locale = LocalResources.current.uiLocale
    val uses = pluralStringResource(R.plurals.lab_conjugations_uses, usesQuantity(stats.weight), formatUses(stats.weight, locale))
    val verbs = pluralStringResource(R.plurals.lab_conjugations_verb_count, stats.verbs.size, stats.verbs.size)
    return stringResource(R.string.lab_conjugations_cell_summary, uses, verbs)
}

@Composable
private fun Verbs(report: ConjugationReport) {
    var all by rememberSaveable(report.language) { mutableStateOf(false) }
    val locale = LocalResources.current.uiLocale
    val shown = if (all) report.verbs else report.verbs.take(VERBS_SHOWN)
    SectionHeader(stringResource(R.string.lab_conjugations_verbs))
    SectionCard {
        shown.forEachIndexed { index, verb ->
            if (index > 0) RowDivider()
            val uses = pluralStringResource(R.plurals.lab_conjugations_uses, usesQuantity(verb.weight), formatUses(verb.weight, locale))
            val order = ConjugationMap.rows(report.language) + ConjugationMap.NON_FINITE
            val tenses = verb.tenses.sortedBy { order.indexOf(it) }.map { stringResource(it.labelResource(report.language)) }
            ListRow(title = verb.lemma, summary = stringResource(R.string.lab_conjugations_verb_tenses, uses, tenses.joinToString(", ")))
        }
    }
    if (!all && report.verbs.size > VERBS_SHOWN) {
        LinguizeButton(stringResource(R.string.lab_conjugations_show_all_verbs), { all = true }, ButtonKind.Text, Modifier.padding(start = 8.dp, top = 4.dp))
    }
}

@Composable
private fun Trend(points: List<MonthPoint>) {
    val locale = LocalResources.current.uiLocale
    val most = points.maxOf { it.cells }.coerceAtLeast(1)
    SectionHeader(stringResource(R.string.lab_conjugations_trend))
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            points.forEach { point ->
                val month = point.month.month.getDisplayName(TextStyle.SHORT, locale)
                val full = point.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
                val description = stringResource(R.string.lab_conjugations_trend_bar, full, point.cells, point.cumulative)
                Column(
                    Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(point.cells.toString(), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((72f * point.cells / most).coerceAtLeast(2f).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            // Lab, Progress IA: bars in the proof colour, an empty month a hairline, like every chart here.
                            .background(if (point.cells == 0) Linguize.colors.rowDivider else Linguize.colors.proof),
                    )
                    Text(month, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
    CardNote(stringResource(R.string.lab_conjugations_trend_note))
}

@Composable
private fun Tried(report: ConjugationReport) {
    SectionHeader(stringResource(R.string.lab_conjugations_tried))
    SectionCard {
        report.tried.take(TRIED_SHOWN).forEachIndexed { index, tried ->
            if (index > 0) RowDivider()
            ListRow(title = tried.form, summary = tried.example.sentence)
        }
    }
    CardNote(stringResource(R.string.lab_conjugations_tried_note))
}

/** A heading-styled title, for the cell page. */
@Composable
internal fun CellTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp).semantics { heading() })
}
