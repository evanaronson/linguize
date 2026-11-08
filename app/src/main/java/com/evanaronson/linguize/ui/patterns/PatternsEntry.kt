package com.evanaronson.linguize.ui.patterns

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.patterns.PatternReport
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SectionCard

/**
 * Home's way in, under History: shown only once there is enough to say (a few counted
 * checks and a recurring group), and gone again when deletions leave too little.
 */
@Composable
fun PatternsHomeCard(patterns: PatternsViewModel, onOpen: () -> Unit) {
    val state by patterns.state.collectAsState()
    val report = state.snapshot?.report?.takeIf { it.worthShowing } ?: return
    Spacer(Modifier.height(12.dp))
    SectionCard {
        ListRow(
            stringResource(R.string.lab_patterns_title),
            homeSummary(report),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_patterns_open),
        ) {
            report.patterns.firstOrNull()?.let {
                Sparkline(it.buckets, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { })
            }
        }
    }
}

/** The History page's way in, above the list. */
@Composable
fun PatternsHistoryRow(onOpen: () -> Unit) {
    SectionCard(Modifier.padding(bottom = 12.dp)) {
        ListRow(
            stringResource(R.string.lab_patterns_title),
            stringResource(R.string.lab_patterns_history_summary),
            onClick = onOpen,
            onClickLabel = stringResource(R.string.lab_patterns_open),
        )
    }
}

@Composable
private fun homeSummary(report: PatternReport): String {
    val names = report.patterns.map { stringResource(it.kind.labelResource) }
    return when (names.size) {
        0 -> ""
        1 -> stringResource(R.string.lab_patterns_home_one, names[0])
        2 -> stringResource(R.string.lab_patterns_home_two, names[0], names[1].lowercaseFirst())
        else -> (names.size - 2).let { more ->
            pluralStringResource(R.plurals.lab_patterns_home_more, more, names[0], names[1].lowercaseFirst(), more)
        }
    }
}

/** "Ser and estar" in the middle of a sentence; leaves names that start with ¿ or a capital pair alone. */
private fun String.lowercaseFirst(): String =
    if (length > 1 && this[1].isUpperCase()) this else replaceFirstChar { it.lowercaseChar() }
