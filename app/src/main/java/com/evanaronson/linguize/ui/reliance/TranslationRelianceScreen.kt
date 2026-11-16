package com.evanaronson.linguize.ui.reliance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.evanaronson.linguize.core.chat.RelianceDirection
import com.evanaronson.linguize.core.chat.RelianceTrend
import com.evanaronson.linguize.core.chat.TranslationReliance
import com.evanaronson.linguize.core.chat.WeekShare
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.progress.ChartLegend
import com.evanaronson.linguize.ui.progress.EmptyCard
import com.evanaronson.linguize.ui.progress.HowItsCounted
import com.evanaronson.linguize.ui.progress.PageLoading
import com.evanaronson.linguize.ui.progress.ProgressCardPadding
import com.evanaronson.linguize.ui.progress.ScopeHeader
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.ZoneId

/** The view model Progress's rows and the Translations page share; null outside the app (previews). */
@Composable
fun translationReliance(): TranslationRelianceViewModel? {
    val app = LocalContext.current.applicationContext as? App ?: return null
    return viewModel(factory = TranslationRelianceViewModel.factory(app))
}

/**
 * "Translation needed for 12 of 40 replies (30%)": the percentage only from
 * [TranslationReliance.MIN_SUMMARY_REPLIES] replies. Null when nothing is measured yet.
 */
@Composable
fun relianceSummary(report: TranslationReliance): String? = when {
    report.isEmpty -> null
    else -> report.percent?.let {
        pluralStringResource(R.plurals.lab_reliance_summary_percent, report.replies, report.needed, report.replies, it)
    } ?: pluralStringResource(R.plurals.lab_reliance_summary, report.replies, report.needed, report.replies)
}

/** "20% in the last 4 weeks, down from 45% the 4 weeks before". */
@Composable
fun relianceTrend(trend: RelianceTrend): String = stringResource(
    when (trend.direction) {
        RelianceDirection.Down -> R.string.lab_reliance_trend_down
        RelianceDirection.Up -> R.string.lab_reliance_trend_up
        RelianceDirection.Same -> R.string.lab_reliance_trend_same
    },
    trend.recentPercent, trend.earlierPercent,
)

/**
 * Lab, Translation reliance: the Translations page, opened from Progress. How often the writer
 * needed a reply's translation in [language], the last 8 weeks as a share per week, and the
 * trend, with how it's counted.
 */
@Composable
fun TranslationRelianceScreen(language: String?) {
    val model = translationReliance() ?: return
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    val report = state.report(language)
    ScreenColumn {
        ScopeHeader(language)
        when {
            report == null -> PageLoading(stringResource(R.string.lab_reliance_title))
            report.isEmpty -> EmptyCard(stringResource(R.string.lab_reliance_empty_title), stringResource(R.string.lab_reliance_empty))
            else -> Report(report)
        }
        HowItsCounted(
            listOf(
                stringResource(R.string.lab_reliance_how_needed),
                stringResource(R.string.lab_reliance_how_screen),
                stringResource(R.string.lab_reliance_how_since),
                stringResource(R.string.lab_reliance_how_trend),
                stringResource(R.string.lab_reliance_how_delete),
            ),
        )
    }
}

@Composable
private fun Report(report: TranslationReliance) {
    SectionCard(contentPadding = ProgressCardPadding) {
        Column(Modifier.semantics(mergeDescendants = true) { }) {
            relianceSummary(report)?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            }
            Quiet(report.trend?.let { relianceTrend(it) } ?: stringResource(R.string.lab_reliance_trend_not_yet), Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Quiet(stringResource(R.string.lab_reliance_kind))
    }
    if (report.hasWeeklyPoints) {
        SectionHeader(stringResource(R.string.lab_reliance_weeks))
        Weeks(report.weeks)
    }
}

/**
 * The last weeks as bars of 0 to 100%, in the style of Time spent's weeks. A week with too few
 * replies has no bar, only the flat baseline. Screen readers hear every week's value.
 */
@Composable
private fun Weeks(weeks: List<WeekShare>) {
    val locale = LocalResources.current.uiLocale
    val zone = ZoneId.systemDefault()
    fun label(week: WeekShare) = shortDate(week.start.atStartOfDay(zone).toInstant().toEpochMilli(), locale = locale)
    val bar = Linguize.colors.proof
    val empty = Linguize.colors.rowDivider
    val tooFew = stringResource(R.string.lab_reliance_week_too_few)
    val values = weeks.map { week -> week.percent?.let { stringResource(R.string.lab_reliance_week_percent, it) } ?: tooFew }
    val description = stringResource(R.string.lab_reliance_weeks_description, label(weeks.first()), values.joinToString(", "))
    SectionCard(contentPadding = ProgressCardPadding) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
            val gap = 6.dp.toPx()
            val width = (size.width - gap * (weeks.size - 1)) / weeks.size
            val radius = CornerRadius(width / 6, width / 6)
            val baseline = 2.dp.toPx()
            weeks.forEachIndexed { index, week ->
                val left = index * (width + gap)
                val percent = week.percent
                // 0% is a real point (nothing needed), drawn as a sliver of the bar's colour.
                val height = if (percent == null) baseline else (size.height * percent / 100f).coerceAtLeast(baseline)
                drawRoundRect(if (percent == null) empty else bar, Offset(left, size.height - height), Size(width, height), radius)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(label(weeks.first()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(stringResource(R.string.lab_time_spent_this_week), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        ChartLegend(bar, stringResource(R.string.lab_reliance_legend))
        Quiet(stringResource(R.string.lab_reliance_weeks_caption), Modifier.padding(start = 18.dp))
    }
}
