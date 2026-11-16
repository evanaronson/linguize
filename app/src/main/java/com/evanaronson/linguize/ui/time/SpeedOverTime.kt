package com.evanaronson.linguize.ui.time

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.time.SpeedChange
import com.evanaronson.linguize.core.time.SpeedDirection
import com.evanaronson.linguize.core.time.SpeedPoint
import com.evanaronson.linguize.core.time.SpeedTrend
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.progress.ChartLegend
import com.evanaronson.linguize.ui.progress.ProgressCardPadding
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.words.languageLabel
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lab, Time spent: Speed over time. The trend in words first (or nothing, when there isn't
 * enough to say), then writing seconds per word and review seconds per change, one point a
 * week (or two), thin periods marked rather than plotted. Each chart's points are also its
 * content description.
 */
@Composable
internal fun SpeedOverTime(trend: SpeedTrend) {
    SectionCard(contentPadding = ProgressCardPadding) {
        val language = trend.language
        if (language == null || (!trend.hasWriting && !trend.hasReview)) {
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(stringResource(R.string.lab_time_spent_speed_empty_title), style = MaterialTheme.typography.titleSmall)
                Quiet(stringResource(R.string.lab_time_spent_speed_empty), Modifier.padding(top = 2.dp))
            }
            if (language != null && trend.dominant) {
                Quiet(stringResource(R.string.lab_time_spent_speed_dominant, languageLabel(language)), Modifier.padding(top = 8.dp))
            }
            if (trend.leftOut > 0) Quiet(stringResource(R.string.lab_time_spent_speed_left_out), Modifier.padding(top = 8.dp))
            return@SectionCard
        }
        trend.change?.let { Text(changeLine(it), style = MaterialTheme.typography.titleSmall) }
        if (trend.dominant) {
            Quiet(stringResource(R.string.lab_time_spent_speed_dominant, languageLabel(language)), Modifier.padding(top = 2.dp))
        }
        if (trend.change != null || trend.dominant) Spacer(Modifier.height(12.dp))

        val twoWeeks = trend.weeksPerPoint == 2
        val writing = Linguize.colors.proof
        val review = MaterialTheme.colorScheme.onSurface
        Series(
            title = stringResource(R.string.lab_time_spent_speed_writing),
            points = trend.points,
            value = { it.msPerWord },
            format = { perWord(it) },
            description = if (twoWeeks) R.string.lab_time_spent_speed_writing_two_weeks else R.string.lab_time_spent_speed_writing_weeks,
            color = writing,
            height = 96.dp,
        )
        Spacer(Modifier.height(16.dp))
        Series(
            title = stringResource(R.string.lab_time_spent_speed_review),
            points = trend.points,
            value = { it.msPerChange },
            format = { duration(it) },
            description = if (twoWeeks) R.string.lab_time_spent_speed_review_two_weeks else R.string.lab_time_spent_speed_review_weeks,
            color = review,
            height = 64.dp,
        )
        Spacer(Modifier.height(12.dp))
        val thinWriting = trend.hasWriting && trend.points.any { it.msPerWord == null }
        val thinReview = trend.hasReview && trend.points.any { it.msPerChange == null }
        if (thinWriting || thinReview) {
            ChartLegend(Linguize.colors.rowDivider, stringResource(R.string.lab_time_spent_speed_thin_legend))
        }
        if (twoWeeks) Quiet(stringResource(R.string.lab_time_spent_speed_two_weeks), Modifier.padding(top = 4.dp))
        if (trend.leftOut > 0) Quiet(stringResource(R.string.lab_time_spent_speed_left_out), Modifier.padding(top = 4.dp))
    }
}

/** "You're writing about 20% faster than 6 weeks ago", "About the same speed as 4 weeks ago". */
@Composable
internal fun changeLine(change: SpeedChange): String = when (change.direction) {
    SpeedDirection.Faster -> pluralStringResource(R.plurals.lab_time_spent_speed_faster, change.weeksAgo, change.percent, change.weeksAgo)
    SpeedDirection.Slower -> pluralStringResource(R.plurals.lab_time_spent_speed_slower, change.weeksAgo, change.percent, change.weeksAgo)
    SpeedDirection.Same -> pluralStringResource(R.plurals.lab_time_spent_speed_same, change.weeksAgo, change.weeksAgo)
}

/** One figure over the periods: its title and latest value, the line, and the first and last dates. */
@Composable
private fun Series(
    title: String,
    points: List<SpeedPoint>,
    value: (SpeedPoint) -> Long?,
    format: @Composable (Long) -> String,
    description: Int,
    color: Color,
    height: Dp,
) {
    val locale = LocalResources.current.uiLocale
    val zone = ZoneId.systemDefault()
    fun label(day: LocalDate) = shortDate(day.atStartOfDay(zone).toInstant().toEpochMilli(), locale = locale)
    val values = points.map(value)
    val latest = values.lastOrNull { it != null }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Quiet(title, Modifier.weight(1f))
        Text(latest?.let { format(it) } ?: stringResource(R.string.lab_time_spent_not_yet),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    }
    if (latest == null) return
    val thin = stringResource(R.string.lab_time_spent_speed_thin)
    val spoken = points.zip(values).map { (point, ms) ->
        stringResource(R.string.lab_time_spent_speed_point, label(point.start), ms?.let { format(it) } ?: thin)
    }.joinToString(", ")
    val said = stringResource(description, spoken)
    Spacer(Modifier.height(8.dp))
    SpeedChart(values, color, Modifier.fillMaxWidth().height(height).semantics { contentDescription = said })
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
        Text(label(points.first().start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(stringResource(R.string.lab_time_spent_this_week), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A line from zero, one place per period: a dot for each period with enough data, joined only
 * to a neighbour that has one too, and a grey mark on the baseline for a thin period. Starting
 * at zero keeps a change looking as big as it is.
 */
@Composable
private fun SpeedChart(values: List<Long?>, color: Color, modifier: Modifier) {
    val grid = Linguize.colors.rowDivider
    Canvas(modifier) {
        val max = (values.filterNotNull().maxOrNull() ?: 1L).coerceAtLeast(1L) * 1.15f
        val dot = 3.dp.toPx()
        val step = size.width / values.size
        fun x(index: Int) = step * (index + 0.5f)
        fun y(ms: Long) = size.height - ms / max * size.height
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        values.forEachIndexed { index, ms ->
            val next = values.getOrNull(index + 1)
            if (ms != null && next != null) {
                drawLine(color, Offset(x(index), y(ms)), Offset(x(index + 1), y(next)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            if (ms != null) drawCircle(color, dot, Offset(x(index), y(ms)))
            else drawCircle(grid, dot, Offset(x(index), size.height - dot))
        }
    }
}
