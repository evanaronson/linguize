package com.evanaronson.linguize.ui.conjugations

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.TextBlock
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale

/** The page title for a cell: "Present, yo" or "Gerund". */
@Composable
fun conjugationCellTitle(language: String, cell: Cell): String {
    val tense = stringResource(cell.tense.labelResource(language))
    return cell.person?.let { stringResource(R.string.lab_conjugations_cell_summary, tense, it.pronoun(language).removeSuffix(".")) } ?: tense
}

/** One cell of the grid: how often, which verbs, and the writer's own sentences. */
@Composable
fun ConjugationCellScreen(model: ConjugationsViewModel, language: String, cell: Cell) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { if (state.reports.isEmpty()) model.refresh() }
    val report = state.reports[language]
    val stats = report?.stats(cell)
    val locale = LocalResources.current.uiLocale
    ScreenColumn {
        Spacer(Modifier.height(8.dp))
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            cell.person?.let {
                Text(stringResource(R.string.lab_conjugations_person_line, stringResource(it.labelResource), it.pronouns(language)), style = MaterialTheme.typography.bodyMedium)
            }
            if (stats == null) {
                Text(stringResource(R.string.lab_conjugations_cell_empty), style = MaterialTheme.typography.bodyLarge)
            } else {
                val uses = pluralStringResource(R.plurals.lab_conjugations_uses, usesQuantity(stats.weight), formatUses(stats.weight, locale))
                Text(uses, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.lab_conjugations_last_used, shortDate(stats.lastUsed, locale = locale)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (stats == null) return@ScreenColumn
        if (stats.sure < Math.round(stats.weight)) CardNote(stringResource(R.string.lab_conjugations_cell_shared))

        SectionHeader(stringResource(R.string.lab_conjugations_cell_verbs))
        SectionCard {
            stats.verbs.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .forEachIndexed { index, (lemma, weight) ->
                    if (index > 0) RowDivider()
                    ListRow(lemma, pluralStringResource(R.plurals.lab_conjugations_uses, usesQuantity(weight), formatUses(weight, locale)))
                }
        }

        SectionHeader(stringResource(R.string.lab_conjugations_cell_examples))
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            stats.examples.forEachIndexed { index, example ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                TextBlock(example.sentence)
                Text(
                    stringResource(R.string.lab_conjugations_example_form, example.form, shortDate(example.at, locale = locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
