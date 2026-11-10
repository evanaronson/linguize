package com.evanaronson.linguize.ui.words

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.vocab.NextWords
import com.evanaronson.linguize.core.vocab.VocabSuggestions
import com.evanaronson.linguize.core.words.CoveragePoint
import com.evanaronson.linguize.core.words.DeckCoverage
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.core.words.LemmaEntry
import com.evanaronson.linguize.core.words.LexemeEntry
import com.evanaronson.linguize.core.words.WordEntry
import com.evanaronson.linguize.core.words.WordFilter
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.core.words.WordList
import com.evanaronson.linguize.core.words.WordRow
import com.evanaronson.linguize.core.words.WordSort
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.FilterDropdown
import androidx.compose.foundation.layout.FlowRow
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.EvidenceFailureNotice
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.progress.ChartLegend
import com.evanaronson.linguize.ui.progress.EmptyCard
import com.evanaronson.linguize.ui.progress.HowItsCounted
import com.evanaronson.linguize.ui.progress.PageLoading
import com.evanaronson.linguize.ui.progress.ScopeHeader
import com.evanaronson.linguize.ui.progress.StatTile
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Words: in one language, every word of a frequency deck and every word the writer typed, in
 * one list. The header shows coverage of the deck and the writer's totals, with one chart of
 * both over time. Filters: All, Used, Not yet (topped by Words to try next) and Used wrong.
 * Each row says how often the word was used right and wrong, and the share right. Tap a word
 * you've used for the sentences you used it in.
 */
@Composable
fun WordsScreen(words: WordsViewModel, historyOn: Boolean?, language: String?, onOpenWord: (language: String?, key: String, level: WordLevel) -> Unit) {
    val state by words.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(words, language) {
        language?.let(words::selectLanguage)
        words.refresh()
    }
    WordsExportEffect(words)
    val defaultName = stringResource(R.string.lab_vocab_gaps_default_name)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) words.pick(context.contentResolver, uri, defaultName)
    }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val mine = com.evanaronson.linguize.ui.components.rememberMyLanguages().map { it.code }

    val shown = state.shown
    if (state.index == null || shown == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (state.evidenceFailed) EvidenceFailureNotice(state.index != null, words::refresh)
            else if (state.index == null) PageLoading(stringResource(R.string.lab_vocab_gaps_loading))
            else EmptyCard(stringResource(R.string.lab_words_empty_title), stringResource(R.string.lab_words_empty))
            state.storageFailure?.let { StorageFailureNotice(it, words::retryStorage) }
            if (historyOn == false) CardNote(stringResource(R.string.history_off))
        }
        return
    }
    val level = state.shownLevel
    val coverage = state.coverage
    val sort = state.shownSort
    val locale = LocalResources.current.uiLocale
    val rows = remember(shown, coverage, level) { WordList.rows(shown, coverage, level) }
    val counts = remember(rows) { WordList.counts(rows) }
    val arranged = remember(rows, state.filter, sort, state.query) {
        WordList.arrange(rows, state.filter, sort, state.query, Locale.forLanguageTag(shown.language ?: locale.toLanguageTag()))
    }
    val growth = remember(shown, coverage, level) { WordList.growth(shown.rows(level), coverage) }

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        if (state.evidenceFailed) item(key = "evidence_failure") { EvidenceFailureNotice(true, words::refresh) }
        item(key = "header") { Header(shown, coverage, state.deckFailed, level) }
        state.storageFailure?.let { failure ->
            item(key = "storage_failure") { StorageFailureNotice(failure, words::retryStorage) }
        }
        item(key = "decks") { DeckPicker(state.decks, state.deckId, words::selectDeck) }
        if (growth.size > 1 || growth.singleOrNull()?.day?.isBefore(LocalDate.now()) == true) item(key = "growth") {
            Growth(growth, coverage)
        }
        item(key = "controls") {
            Controls(state, level, shown.morphology, coverage != null, counts, sort, words)
            if (level != WordLevel.Forms && shown.ambiguousForms > 0) {
                Quiet(stringResource(R.string.lab_words_ambiguous_note), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
        }
        if (state.filter == WordFilter.NotYet && coverage != null && state.query.isBlank()) item(key = "next") {
            TryNext(coverage) { text -> context.copyToClipboard(text) }
        }
        if (arranged.isEmpty()) {
            item(key = "none") {
                SectionCard(contentPadding = cardPadding) { Quiet(stringResource(R.string.lab_words_no_match)) }
            }
        }
        itemsIndexed(arranged, key = { _, row -> row.key }, contentType = { _, _ -> "word" }) { position, row ->
            Column(Modifier.cardSegment(first = position == 0, last = position == arranged.lastIndex).padding(horizontal = 20.dp)) {
                if (position > 0) RowDivider()
                WordRowItem(row, coverage != null, shown.morphology) { onOpenWord(shown.language, row.key, level) }
            }
        }
        item(key = "footer") {
            Column {
                if (shown.names.isNotEmpty()) {
                    val listed = shown.names.take(MAX_NAMES).joinToString(", ") { it.written }
                    CardNote(pluralStringResource(R.plurals.lab_words_names, shown.names.size, shown.names.size, listed))
                }
                if (shown.words.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        LinguizeButton(stringResource(R.string.lab_words_export), words::exportCsv, ButtonKind.Text,
                            enabled = state.exportReady)
                    }
                }
                YourDecks(state, onImport = { picker.launch(DECK_MIME_TYPES) }, onDelete = { deleting = it })
                HowItWorks(shown.morphology)
                SectionHeader(stringResource(R.string.lab_vocab_gaps_about_lists))
                SectionCard(contentPadding = cardPadding) { Quiet(stringResource(R.string.lab_vocab_gaps_attribution)) }
                if (historyOn == false) CardNote(stringResource(R.string.history_off))
            }
        }
    }

    state.pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            // Lab, language-generic: the writer's languages too, so a deck can be imported for any of them.
            languages = (listOfNotNull(shown.language) + mine + BundledDecks.languages + state.languages).distinct(),
            onImport = words::confirmImport,
            onCancel = words::cancelImport,
            saving = state.importing,
            saveFailed = state.importMessage == ImportMessage.SaveFailed,
        )
    }
    deleting?.let { id -> DeleteDeckDialog(onDelete = { deleting = null; words.deleteDeck(id) }, onCancel = { deleting = null }) }
}

private const val MAX_NAMES = 8

/** A language's name for the switch: Catalan, Spanish, or another the corpus holds. */
@Composable
internal fun languageLabel(code: String?): String {
    val locale = LocalResources.current.uiLocale
    return Language.forCode(code)?.displayName()
        ?: code?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }?.takeIf { it.isNotBlank() && it != code }
            ?.replaceFirstChar { it.titlecase(locale) }
        ?: stringResource(R.string.lab_words_other_language)
}

/** Coverage of the deck, then the writer's totals. */
@Composable
private fun Header(words: LanguageWords, coverage: DeckCoverage?, deckFailed: Boolean, level: WordLevel) {
    // Lab, Progress IA: the language, then a summary, as on every page Progress opens.
    words.language?.let { ScopeHeader(it) } ?: SectionHeader(languageLabel(null))
    SectionCard(contentPadding = cardPadding) {
        if (words.words.isEmpty()) {
            Text(stringResource(R.string.lab_vocab_gaps_empty_title, languageLabel(words.language)), style = MaterialTheme.typography.titleSmall)
            Quiet(stringResource(R.string.lab_vocab_gaps_empty))
            Spacer(Modifier.height(16.dp))
        }
        if (coverage != null) {
            val headline = coverage.headline
            if (headline != null && words.words.isNotEmpty()) {
                Text(
                    stringResource(
                        when {
                            coverage.deck.formsOnly -> R.string.lab_vocab_gaps_level_forms
                            coverage.deck.bundled -> R.string.lab_vocab_gaps_level_common
                            else -> R.string.lab_vocab_gaps_level_deck
                        },
                        headline.percent, headline.top,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Quiet(stringResource(R.string.lab_vocab_gaps_level_caveat))
                Spacer(Modifier.height(16.dp))
            }
            coverage.milestones.forEachIndexed { index, milestone ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                MilestoneBar(milestone, isAll = index == coverage.milestones.lastIndex && milestone.top == coverage.deck.size)
            }
            if (coverage.bySpelling > 0) {
                Spacer(Modifier.height(8.dp))
                Quiet(pluralStringResource(R.plurals.lab_vocab_gaps_by_spelling, coverage.bySpelling, coverage.bySpelling))
            }
            if (coverage.deck.formsOnly) {
                Spacer(Modifier.height(8.dp))
                Quiet(stringResource(R.string.lab_vocab_gaps_forms_only))
            }
            if (words.words.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                RowDivider()
                Spacer(Modifier.height(16.dp))
            }
        } else if (deckFailed) {
            Quiet(stringResource(R.string.lab_vocab_gaps_failed))
            Spacer(Modifier.height(16.dp))
        }
        if (words.words.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Stat(words.distinctWords, stringResource(R.string.lab_words_stat_distinct), Modifier.weight(1f))
                if (words.morphology) {
                    Stat(words.distinctLexemes, stringResource(R.string.lab_words_stat_lexemes), Modifier.weight(1f))
                    Stat(words.distinctLemmas, stringResource(R.string.lab_words_stat_lemmas), Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat(words.totalWords, stringResource(R.string.lab_words_stat_total), Modifier.weight(1f))
                Stat(words.wordsThisMonth, stringResource(R.string.lab_words_stat_month), Modifier.weight(1f))
            }
            if (words.newThisMonth > 0) {
                Spacer(Modifier.height(12.dp))
                Quiet(pluralStringResource(R.plurals.lab_words_new_this_month, words.newThisMonth, words.newThisMonth))
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    val locale = LocalResources.current.uiLocale
    StatTile(String.format(locale, "%,d", value), label, modifier)
}

/**
 * One chart: the writer's distinct words by day (at the level shown), and how many of the
 * deck's top group they had used by then. The second is a subset of the first, so both
 * share one axis.
 */
@Composable
private fun Growth(points: List<CoveragePoint>, coverage: DeckCoverage?) {
    val locale = LocalResources.current.uiLocale
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun dayLabel(day: LocalDate) = shortDate(day.atStartOfDay(zone).toInstant().toEpochMilli(), locale = locale)
    val first = points.first()
    val last = points.last()
    val wordsColor = Linguize.colors.proof
    val topColor = MaterialTheme.colorScheme.onSurface
    SectionHeader(stringResource(R.string.lab_words_growth))
    SectionCard(contentPadding = cardPadding) {
        val description = if (coverage != null) {
            stringResource(R.string.lab_words_growth_both_description, first.words, dayLabel(first.day), last.words, dayLabel(last.day),
                coverage.trendTop, last.top)
        } else {
            stringResource(R.string.lab_words_growth_description, first.words, dayLabel(first.day), last.words, dayLabel(last.day))
        }
        GrowthChart(points, today, coverage != null, wordsColor, topColor,
            Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(dayLabel(first.day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(dayLabel(today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        ChartLegend(wordsColor, stringResource(R.string.lab_words_growth_words_legend, last.words))
        if (coverage != null) ChartLegend(topColor, stringResource(R.string.lab_words_growth_top_legend, last.top, coverage.trendTop))
    }
}

@Composable
private fun GrowthChart(points: List<CoveragePoint>, today: LocalDate, showTop: Boolean, wordsColor: Color, topColor: Color, modifier: Modifier) {
    val grid = Linguize.colors.rowDivider
    Canvas(modifier) {
        val startDay = points.first().day.toEpochDay()
        val endDay = maxOf(today.toEpochDay(), points.last().day.toEpochDay(), startDay + 1)
        val max = points.maxOf { it.words }.coerceAtLeast(points.maxOf { it.top }).coerceAtLeast(1)
        fun x(day: Long) = (day - startDay).toFloat() / (endDay - startDay) * size.width
        fun y(value: Int) = size.height - value.toFloat() / max * (size.height - 4.dp.toPx())
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        fun line(value: (CoveragePoint) -> Int): Path {
            val path = Path()
            var previous = 0
            path.moveTo(0f, y(0))
            points.forEach { point ->
                val px = x(point.day.toEpochDay())
                path.lineTo(px, y(previous))
                path.lineTo(px, y(value(point)))
                previous = value(point)
            }
            path.lineTo(size.width, y(previous))
            return path
        }
        val words = line { it.words }
        val area = Path().apply {
            addPath(words)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, wordsColor.copy(alpha = 0.12f))
        drawPath(words, wordsColor, style = Stroke(width = 2.dp.toPx()))
        if (showTop) drawPath(line { it.top }, topColor, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun Controls(
    state: WordsState,
    level: WordLevel,
    morphology: Boolean,
    hasDeck: Boolean,
    counts: Map<WordFilter, Int>,
    sort: WordSort,
    words: WordsViewModel,
) {
    SectionHeader(stringResource(R.string.lab_words_list))
    LinguizeTextField(
        value = state.query,
        onValueChange = words::search,
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.lab_words_search),
        accessibleLabel = stringResource(R.string.lab_words_search),
        singleLine = true,
    )
    // Filters are dropdowns, never buttons: they choose what the list shows.
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        FilterDropdown(
            selected = state.filter,
            options = WordFilter.entries.filter { hasDeck || it != WordFilter.NotYet },
            label = { stringResource(R.string.lab_vocab_gaps_filter_count, filterLabel(it), counts[it] ?: 0) },
            description = stringResource(R.string.lab_words_filter),
            onSelect = words::filter,
            labelled = true,
        )
        FilterDropdown(
            selected = sort,
            options = WordSort.entries.filter { hasDeck || it != WordSort.Rank },
            label = { sortLabel(it) },
            description = stringResource(R.string.lab_words_sort),
            onSelect = words::sortBy,
            labelled = true,
        )
        // Lexemes and lemmas only mean something once the lemmatizer knows morphology.
        if (morphology) FilterDropdown(
            selected = level,
            options = WordLevel.entries,
            label = { levelLabel(it) },
            description = stringResource(R.string.lab_words_levels),
            onSelect = words::countBy,
            labelled = true,
        )
    }
}

@Composable
private fun filterLabel(filter: WordFilter): String = stringResource(
    when (filter) {
        WordFilter.All -> R.string.lab_vocab_gaps_filter_all
        WordFilter.Used -> R.string.lab_vocab_gaps_filter_used
        WordFilter.NotYet -> R.string.lab_vocab_gaps_not_yet
        WordFilter.Tried -> R.string.lab_words_filter_tried
    },
)

@Composable
private fun sortLabel(sort: WordSort): String = stringResource(
    when (sort) {
        WordSort.Rank -> R.string.lab_words_sort_rank
        WordSort.MostUsed -> R.string.lab_words_sort_most_used
        WordSort.MostMisused -> R.string.lab_words_sort_most_misused
        WordSort.Newest -> R.string.lab_words_sort_newest
        WordSort.Alphabetical -> R.string.lab_words_sort_az
    },
)

/** The highest-ranked rows not yet used, by part of speech, and Copy 5 words. */
@Composable
private fun TryNext(coverage: DeckCoverage, onCopy: (String) -> Unit) {
    val used = coverage.used
    val groups = remember(coverage) { VocabSuggestions.next(coverage.deck, used) }
    val targets = remember(coverage) { VocabSuggestions.targets(coverage.deck, used) }
    SectionCard(Modifier.padding(bottom = 12.dp), contentPadding = cardPadding) {
        Text(stringResource(R.string.lab_vocab_gaps_try_next), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (groups.isEmpty()) {
            Quiet(stringResource(R.string.lab_vocab_gaps_all_used))
            return@SectionCard
        }
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Text(stringResource(groupLabel(group.group)), style = MaterialTheme.typography.titleSmall)
            Text(group.entries.joinToString(", ") { it.lemma }, style = MaterialTheme.typography.bodyLarge)
        }
        if (targets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val text = stringResource(R.string.lab_vocab_gaps_copy_text, targets.joinToString(", ") { it.lemma })
            LinguizeButton(stringResource(R.string.lab_vocab_gaps_copy_targets), { onCopy(text) }, ButtonKind.Tonal)
            Quiet(stringResource(R.string.lab_vocab_gaps_copy_caption), Modifier.padding(top = 4.dp))
        }
    }
}

private fun groupLabel(group: String): Int = when (group) {
    Pos.Verb.token -> R.string.lab_vocab_gaps_group_verb
    Pos.Noun.token -> R.string.lab_vocab_gaps_group_noun
    Pos.Adjective.token -> R.string.lab_vocab_gaps_group_adj
    Pos.Adverb.token -> R.string.lab_vocab_gaps_group_adv
    NextWords.ALL -> R.string.lab_vocab_gaps_group_all
    else -> R.string.lab_vocab_gaps_group_other
}

/** A row: deck rank, the word with its part of speech and what it is, and the writer's use or Not yet. */
@Composable
private fun WordRowItem(row: WordRow, hasDeck: Boolean, morphology: Boolean, onOpen: () -> Unit) {
    val locale = LocalResources.current.uiLocale
    val counts = row.counts
    val details = buildList {
        row.pos?.let { add(stringResource(it.labelResource)) }
        row.gloss?.let { add(it) }
        when (counts) {
            is WordEntry -> if (morphology && counts.ambiguous) {
                add(stringResource(R.string.lab_words_row_could_be, counts.lexemes.map { lexemeTitle(it) }.joinToString(", ")))
            }
            is LexemeEntry -> add(formsLine(counts.forms, counts.ambiguousForms))
            is LemmaEntry -> add(stringResource(R.string.lab_words_row_forms, counts.forms.joinToString(", ")))
            else -> Unit
        }
        if (row.notInList) add(stringResource(R.string.lab_words_not_in_list))
    }.joinToString(", ")
    val resources = LocalResources.current
    val (status, last) = remember(counts, resources, locale) { rowUse(resources, counts) { shortDate(it, locale = locale) } }
    val openLabel = stringResource(R.string.lab_words_open_word)
    Row(
        Modifier.fillMaxWidth()
            .then(if (counts != null) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen) else Modifier.semantics(mergeDescendants = true) { })
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasDeck) {
            Text(
                row.rank?.toString().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge)
            if (details.isNotEmpty()) Quiet(details)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (counts?.known == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** "Forms: el, la (ambiguous: la)". */
@Composable
internal fun formsLine(forms: List<String>, ambiguous: Set<String>): String {
    val list = forms.joinToString(", ")
    val marked = forms.filter { it in ambiguous }
    return if (marked.isEmpty()) stringResource(R.string.lab_words_row_forms, list)
    else stringResource(R.string.lab_words_row_forms_ambiguous, list, marked.joinToString(", "))
}

@Composable
private fun HowItWorks(morphology: Boolean) {
    HowItsCounted(
        listOfNotNull(
            stringResource(R.string.lab_words_how),
            stringResource(R.string.lab_words_how_tried),
            stringResource(R.string.lab_words_how_accuracy),
            stringResource(R.string.lab_vocab_gaps_how_text),
            if (morphology) stringResource(R.string.lab_words_how_levels) else null,
            stringResource(R.string.lab_words_how_ambiguous),
            stringResource(R.string.lab_words_how_delete),
        ),
    )
}

/** Hands a finished CSV to the share sheet, once. */
@Composable
private fun WordsExportEffect(words: WordsViewModel) {
    val context = LocalContext.current
    val export by words.export.collectAsState()
    LaunchedEffect(export) {
        export?.let {
            if (it !is WordsExport.Ready || words.isExportCurrent(it)) share(context, it)
            words.exportHandled()
        }
    }
}

private fun share(context: Context, export: WordsExport) {
    val uri = when (export) {
        is WordsExport.Ready -> export.uri
        WordsExport.Failed -> {
            Toast.makeText(context, R.string.lab_words_export_failed, Toast.LENGTH_LONG).show()
            return
        }
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.openExternal(Intent.createChooser(send, context.getString(R.string.lab_words_export_chooser)))
}
