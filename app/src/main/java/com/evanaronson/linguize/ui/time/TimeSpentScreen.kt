package com.evanaronson.linguize.ui.time

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.time.EstimateBasis
import com.evanaronson.linguize.core.time.TimeReport
import com.evanaronson.linguize.core.time.TimeTotals
import com.evanaronson.linguize.core.time.WeekTime
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.patterns.Sparkline
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.words.languageLabel
import java.time.ZoneId

private val cardPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
private fun timeSpent(): TimeSpentViewModel? {
    val app = LocalContext.current.applicationContext as? App ?: return null
    return viewModel(factory = TimeSpentViewModel.factory(app))
}

/** Progress › Time: this week's total, with a small chart of the last weeks. Called with one line. */
@Composable
fun TimeSpentSection(onOpen: () -> Unit) {
    val model = timeSpent() ?: return
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val report = state.report
    SectionHeader(stringResource(R.string.lab_time_spent_section))
    ListCard({
        ListRow(
            stringResource(R.string.lab_time_spent_title),
            when {
                report == null -> null
                report.isEmpty -> stringResource(R.string.lab_time_spent_row_empty)
                else -> stringResource(R.string.lab_time_spent_row_summary, duration(report.thisWeek.totalMs))
            },
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_time_spent_open),
        ) {
            report?.takeUnless { it.isEmpty }?.let { r ->
                Sparkline(r.weeks.map { seconds(it.totals.totalMs) }, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { })
            }
        }
    })
}

/** The Time page: totals, the writing and reviewing split, averages, languages, weeks, and how it's counted. */
@Composable
fun TimeSpentScreen(historyOn: Boolean?) {
    val model = timeSpent() ?: return
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val report = state.report
    ScreenColumn {
        if (historyOn == false) {
            SectionCard(contentPadding = cardPadding) { Quiet(stringResource(R.string.lab_time_spent_history_off)) }
            Spacer(Modifier.height(16.dp))
        }
        when {
            report == null -> Unit
            report.isEmpty -> {
                SectionCard(contentPadding = cardPadding) {
                    Text(stringResource(R.string.lab_time_spent_empty_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Quiet(stringResource(R.string.lab_time_spent_empty))
                }
            }
            else -> Report(report)
        }
        HowItsCounted()
    }
}

@Composable
private fun Report(report: TimeReport) {
    SectionHeader(stringResource(R.string.lab_time_spent_this_week))
    Totals(report.thisWeek)
    SectionHeader(stringResource(R.string.lab_time_spent_all_time))
    Totals(report.allTime)

    SectionHeader(stringResource(R.string.lab_time_spent_averages))
    val notYet = stringResource(R.string.lab_time_spent_not_yet)
    ListCard(
        { ListRow(stringResource(R.string.lab_time_spent_per_message), report.allTime.perMessageMs?.let { duration(it) } ?: notYet) },
        { ListRow(stringResource(R.string.lab_time_spent_per_word), report.msPerWord?.let { perWord(it) } ?: notYet) },
        { ListRow(stringResource(R.string.lab_time_spent_per_change), report.msPerChange?.let { duration(it) } ?: notYet) },
    )

    if (report.languages.isNotEmpty()) {
        SectionHeader(stringResource(R.string.lab_time_spent_languages))
        ListCard(*report.languages.map { language ->
            @Composable {
                val totals = language.totals
                val speed = language.msPerWord?.let { stringResource(R.string.lab_time_spent_per_word) + ": " + perWord(it) }
                ListRow(
                    languageLabel(language.language),
                    listOfNotNull(
                        stringResource(R.string.lab_time_spent_language_summary, duration(totals.totalMs),
                            pluralStringResource(R.plurals.lab_time_spent_messages, totals.messages, totals.messages)),
                        speed,
                        if (totals.estimatedMessages > 0) basisLabel(language.estimateBasis) else null,
                    ).joinToString("\n"),
                )
            }
        }.toTypedArray())
    }

    SectionHeader(stringResource(R.string.lab_time_spent_weeks))
    Weeks(report.weeks)
}

/** One period: the total, then writing (measured and estimated) and reviewing. */
@Composable
private fun Totals(totals: TimeTotals) {
    val measured = Linguize.colors.proof
    val estimated = measured.copy(alpha = 0.4f)
    val review = MaterialTheme.colorScheme.onSurface
    SectionCard(contentPadding = cardPadding) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(duration(totals.totalMs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Quiet(pluralStringResource(R.plurals.lab_time_spent_messages, totals.messages, totals.messages), Modifier.padding(bottom = 4.dp))
        }
        if (totals.totalMs > 0) {
            Spacer(Modifier.height(12.dp))
            val description = stringResource(R.string.lab_time_spent_split_description, duration(totals.writingMs), duration(totals.reviewMs))
            SplitBar(
                listOf(totals.measuredWritingMs to measured, totals.estimatedWritingMs to estimated, totals.reviewMs to review),
                Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = description },
            )
        }
        Spacer(Modifier.height(12.dp))
        Legend(measured, stringResource(R.string.lab_time_spent_writing) + ": " + duration(totals.writingMs))
        if (totals.estimatedWritingMs > 0) {
            Quiet(
                stringResource(R.string.lab_time_spent_writing_split, duration(totals.measuredWritingMs), duration(totals.estimatedWritingMs)),
                Modifier.padding(start = 18.dp),
            )
        }
        Legend(review, stringResource(R.string.lab_time_spent_reviewing) + ": " + duration(totals.reviewMs))
    }
}

/** The last weeks as bars: measured time solid, estimated writing lighter on top. */
@Composable
private fun Weeks(weeks: List<WeekTime>) {
    val locale = LocalResources.current.uiLocale
    val zone = ZoneId.systemDefault()
    fun label(week: WeekTime) = shortDate(week.start.atStartOfDay(zone).toInstant().toEpochMilli(), locale = locale)
    val measured = Linguize.colors.proof
    val estimated = measured.copy(alpha = 0.4f)
    val empty = Linguize.colors.rowDivider
    SectionCard(contentPadding = cardPadding) {
        val description = stringResource(R.string.lab_time_spent_weeks_description, label(weeks.first()), duration(weeks.last().totals.totalMs))
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
            val max = weeks.maxOf { it.totals.totalMs }.coerceAtLeast(1L)
            val gap = 6.dp.toPx()
            val width = (size.width - gap * (weeks.size - 1)) / weeks.size
            val radius = CornerRadius(width / 6, width / 6)
            weeks.forEachIndexed { index, week ->
                val left = index * (width + gap)
                val total = week.totals.totalMs
                if (total == 0L) {
                    drawRoundRect(empty, Offset(left, size.height - 2.dp.toPx()), Size(width, 2.dp.toPx()), radius)
                    return@forEachIndexed
                }
                val solid = size.height * week.totals.measuredMs / max
                val light = size.height * week.totals.estimatedWritingMs / max
                drawRoundRect(measured, Offset(left, size.height - solid), Size(width, solid), radius)
                if (light > 0f) drawRoundRect(estimated, Offset(left, size.height - solid - light), Size(width, light), radius)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(label(weeks.first()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(stringResource(R.string.lab_time_spent_this_week), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Legend(measured, stringResource(R.string.lab_time_spent_measured))
        Legend(estimated, stringResource(R.string.lab_time_spent_estimated))
    }
}

@Composable
private fun HowItsCounted() {
    SectionHeader(stringResource(R.string.lab_time_spent_how))
    SectionCard(contentPadding = cardPadding) {
        listOf(
            R.string.lab_time_spent_how_writing,
            R.string.lab_time_spent_how_review,
            R.string.lab_time_spent_how_estimate,
            R.string.lab_time_spent_how_limits,
        ).forEachIndexed { index, text ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Quiet(stringResource(text))
        }
    }
}

/** Parts of one whole, side by side; decorative, the numbers are also in words. */
@Composable
private fun SplitBar(parts: List<Pair<Long, Color>>, modifier: Modifier) {
    val empty = Linguize.colors.rowDivider
    Canvas(modifier) {
        val total = parts.sumOf { it.first }.coerceAtLeast(1L)
        drawRoundRect(empty, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
        var left = 0f
        parts.forEach { (value, color) ->
            val width = size.width * value / total
            if (width > 0f) drawRect(color, Offset(left, 0f), Size(width, size.height))
            left += width
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape).clearAndSetSemantics { })
        Spacer(Modifier.width(8.dp))
        Quiet(text)
    }
}

@Composable
private fun basisLabel(basis: EstimateBasis): String = stringResource(
    when (basis) {
        EstimateBasis.Language -> R.string.lab_time_spent_basis_language
        EstimateBasis.AllLanguages -> R.string.lab_time_spent_basis_all
        EstimateBasis.Default -> R.string.lab_time_spent_basis_default
    },
)

/** "45 s", "12 min", "2 h 5 min". */
@Composable
internal fun duration(ms: Long): String {
    val parts = DurationParts.of(ms)
    return when {
        parts.hours > 0 -> stringResource(R.string.lab_time_spent_hours, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(R.string.lab_time_spent_minutes, parts.minutes)
        else -> stringResource(R.string.lab_time_spent_seconds, parts.seconds)
    }
}

/** Seconds a word, one decimal: "3.8 s". */
@Composable
private fun perWord(ms: Long): String {
    val locale = LocalResources.current.uiLocale
    return stringResource(R.string.lab_time_spent_per_word_value, String.format(locale, "%.1f", ms / 1000.0))
}

private fun seconds(ms: Long): Int = (ms / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** A duration split for display: under a minute in seconds, else minutes rounded, hours above an hour. */
internal data class DurationParts(val hours: Int, val minutes: Int, val seconds: Int) {
    companion object {
        fun of(ms: Long): DurationParts {
            val clamped = ms.coerceAtLeast(0L)
            if (clamped < 60_000L) return DurationParts(0, 0, ((clamped + 500) / 1000).toInt().coerceAtMost(59))
            val totalMinutes = ((clamped + 30_000L) / 60_000L).toInt()
            return DurationParts(totalMinutes / 60, totalMinutes % 60, 0)
        }
    }
}
