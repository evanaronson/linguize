package com.evanaronson.linguize.ui.patterns

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.patterns.PatternReport

/** Progress's summary of Your patterns: the top groups by name ("Accents and ser and estar"). */
@Composable
internal fun patternsSummary(report: PatternReport): String {
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
