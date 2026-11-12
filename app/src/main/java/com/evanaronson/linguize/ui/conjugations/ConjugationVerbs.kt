package com.evanaronson.linguize.ui.conjugations

import com.evanaronson.linguize.ui.components.EvidenceFailureNotice

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.conjugations.Cell
import com.evanaronson.linguize.core.conjugations.ConjugationMap
import com.evanaronson.linguize.core.conjugations.VerbList
import com.evanaronson.linguize.core.conjugations.VerbPreview
import com.evanaronson.linguize.core.conjugations.VerbRow
import com.evanaronson.linguize.core.conjugations.VerbSort
import com.evanaronson.linguize.core.corpus.Lexeme
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.core.words.key
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.FilterDropdown
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.progress.PageLoading
import com.evanaronson.linguize.ui.progress.ProgressCardPadding
import java.util.Locale

/*
 * Lab, Conjugations page: verbs on Your conjugations and on a cell's page are a capped
 * preview (most used first) with "Show all N verbs", which opens [ConjugationVerbsScreen]:
 * every verb, or a cell's, with search and a sort. A verb's counts and sentences are its
 * Words page, so each row opens that; the rows here carry only what Words can't show: the
 * forms and tenses a verb was used in, or its uses in one cell.
 */

/** The cells of the Other forms row, in the grid's order. */
internal fun otherFormCells(): List<Cell> = ConjugationMap.NON_FINITE.map { Cell(it, null) }

/** The short column header of a non-finite form ("Inf."); the full name is [labelResource]. */
@StringRes
internal fun Tense.abbreviationResource(): Int = when (this) {
    Tense.Infinitive -> R.string.lab_conjugations_page_abbr_infinitive
    Tense.Gerund -> R.string.lab_conjugations_page_abbr_gerund
    Tense.Participle -> R.string.lab_conjugations_page_abbr_participle
    else -> labelResource("es")
}

/** A verb's row key in Words at the lexeme level: "hablar/v". */
fun verbWordKey(lemma: String): String = Lexeme(lemma, Pos.Verb).key()

/** The Words level a verb opens at. */
val VERB_WORD_LEVEL: WordLevel = WordLevel.Lexemes

/** The top bar's title of the verbs page: "Verbs you use", or "Verbs in Present, yo". */
@Composable
fun conjugationVerbsTitle(language: String, cell: Cell?): String =
    cell?.let { stringResource(R.string.lab_conjugations_page_cell_verbs_title, conjugationCellTitle(language, it)) }
        ?: stringResource(R.string.lab_conjugations_verbs)

/** A capped list of verbs in a card, with "Show all N verbs" when there are more. */
@Composable
internal fun VerbPreviewCard(preview: VerbPreview, language: String, cell: Cell?, onOpenVerb: (String) -> Unit, onShowAll: () -> Unit) {
    SectionCard {
        preview.shown.forEachIndexed { index, verb ->
            if (index > 0) RowDivider()
            VerbRowItem(verb, language, cell, onOpenVerb)
        }
    }
    if (preview.more) {
        LinguizeButton(
            pluralStringResource(R.plurals.lab_conjugations_page_show_all, preview.total, preview.total),
            onShowAll, ButtonKind.Text, Modifier.padding(start = 8.dp, top = 4.dp),
        )
    } else {
        CardNote(stringResource(R.string.lab_conjugations_page_verbs_note))
    }
}

/**
 * One verb: its forms and tenses (named as in [language]: Catalan's "Simple past"), or within
 * a [cell] its uses there. Opens its Words page.
 */
@Composable
internal fun VerbRowItem(verb: VerbRow, language: String, cell: Cell?, onOpenVerb: (String) -> Unit, modifier: Modifier = Modifier) {
    val summary = if (cell == null) {
        val tenses = verb.tenses.map { stringResource(it.labelResource(language)) }.joinToString(", ")
        pluralStringResource(R.plurals.lab_conjugations_page_verb_forms, verb.cells, verb.cells, tenses)
    } else {
        val locale = LocalResources.current.uiLocale
        pluralStringResource(R.plurals.lab_conjugations_uses, usesQuantity(verb.weight), formatUses(verb.weight, locale))
    }
    ListRow(
        title = verb.lemma,
        summary = summary,
        modifier = modifier,
        onClick = { onOpenVerb(verb.lemma) },
        onClickLabel = stringResource(R.string.lab_conjugations_page_open_word),
    )
}

@Composable
private fun sortLabel(sort: VerbSort): String = stringResource(
    when (sort) {
        VerbSort.MostUsed -> R.string.lab_conjugations_page_sort_used
        VerbSort.MostForms -> R.string.lab_conjugations_page_sort_forms
        VerbSort.Alphabetical -> R.string.lab_conjugations_page_sort_az
    },
)

/**
 * Every verb the writer used in [language], or those of [cell]: a search, a sort, and the
 * whole list, built for hundreds of verbs. Each row opens the verb in Words.
 */
@Composable
fun ConjugationVerbsScreen(model: ConjugationsViewModel, language: String, cell: Cell?, onOpenVerb: (language: String, lemma: String) -> Unit) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { if (state.reports.isEmpty()) model.refresh() }
    val report = state.reports[language]
    var query by rememberSaveable(language, cell?.token) { mutableStateOf("") }
    var sortToken by rememberSaveable(language, cell?.token) { mutableStateOf(VerbSort.MostUsed.token) }
    val sort = VerbSort.read(sortToken) ?: VerbSort.MostUsed
    val rows = remember(report, cell) { report?.let { VerbList.rows(it, cell) }.orEmpty() }
    val arranged = remember(rows, sort, query) { VerbList.arrange(rows, sort, query, Locale.forLanguageTag(language)) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        if (state.failed) item(key = "evidence_failure") { EvidenceFailureNotice(report != null, model::refresh) }
        if (report == null) {
            if (state.loading) item(key = "loading") { PageLoading(stringResource(R.string.lab_conjugations_loading)) }
            return@LazyColumn
        }
        item(key = "controls") {
            Column {
                SectionHeader(pluralStringResource(R.plurals.lab_conjugations_verb_count, rows.size, rows.size))
                LinguizeTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.lab_conjugations_page_search),
                    accessibleLabel = stringResource(R.string.lab_conjugations_page_search),
                    singleLine = true,
                )
                FilterDropdown(
                    selected = sort,
                    options = VerbList.sorts(cell),
                    label = { sortLabel(it) },
                    description = stringResource(R.string.lab_conjugations_page_sort),
                    onSelect = { sortToken = it.token },
                    modifier = Modifier.padding(vertical = 4.dp),
                    labelled = true,
                )
            }
        }
        if (arranged.isEmpty()) {
            item(key = "none") {
                SectionCard(contentPadding = ProgressCardPadding) { Quiet(stringResource(R.string.lab_conjugations_page_no_match)) }
            }
        }
        itemsIndexed(arranged, key = { _, verb -> verb.lemma }, contentType = { _, _ -> "verb" }) { position, verb ->
            Column(Modifier.cardSegment(first = position == 0, last = position == arranged.lastIndex)) {
                if (position > 0) RowDivider()
                VerbRowItem(verb, language, cell, onOpenVerb = { onOpenVerb(language, it) })
            }
        }
        item(key = "note") {
            Column {
                CardNote(stringResource(R.string.lab_conjugations_page_verbs_note))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
