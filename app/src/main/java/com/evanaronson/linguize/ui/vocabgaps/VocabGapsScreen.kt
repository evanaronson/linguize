package com.evanaronson.linguize.ui.vocabgaps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.DeckChoice
import com.evanaronson.linguize.VocabView
import com.evanaronson.linguize.core.vocab.DeckEntry
import com.evanaronson.linguize.core.vocab.DeckParse
import com.evanaronson.linguize.core.vocab.Milestone
import com.evanaronson.linguize.core.vocab.MonthPoint
import com.evanaronson.linguize.core.vocab.NextWords
import com.evanaronson.linguize.core.corpus.Pos
import com.evanaronson.linguize.core.vocab.VocabLevel
import com.evanaronson.linguize.core.vocab.VocabReport
import com.evanaronson.linguize.core.vocab.VocabSuggestions
import com.evanaronson.linguize.data.vocabgaps.BundledDecks
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Vocabulary gaps: a frequency deck from the top, each word marked used (with the writer's
 * own sentences) or not yet; coverage of the top groups, the trend by month, and the
 * highest-ranked gaps to try next. Imported decks are managed at the bottom.
 */
@Composable
fun VocabGapsScreen(model: VocabGapsViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val defaultName = stringResource(R.string.lab_vocab_gaps_default_name)
    LaunchedEffect(model) { model.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.pick(context.contentResolver, uri, defaultName)
    }
    var openWord by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val view = state.view
    val rows = remember(view, state.filter) { view?.let { filtered(it, state.filter) }.orEmpty() }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        item(key = "intro") { CardNote(stringResource(R.string.lab_vocab_gaps_intro)) }
        item(key = "pickers") { Pickers(state, model::selectLanguage, model::selectDeck) }
        when {
            view == null && state.loading -> item(key = "loading") { Loading() }
            view == null && state.failed -> item(key = "failed") {
                SectionCard(Modifier.padding(top = 12.dp), contentPadding = cardPadding) {
                    Text(stringResource(R.string.lab_vocab_gaps_failed), style = MaterialTheme.typography.titleSmall)
                    LinguizeButton(stringResource(R.string.lab_vocab_gaps_retry), model::refresh, ButtonKind.Text)
                }
            }
            view != null -> {
                if (view.hasLexemes) item(key = "level") { LevelSwitch(state.level, model::setLevel) }
                item(key = "coverage") { Coverage(view) }
                if (view.report.trend.isNotEmpty()) item(key = "trend") { Trend(view.report) }
                item(key = "next") { TryNext(view) { words -> context.copyToClipboard(words) } }
                item(key = "filters") { Filters(view, state.filter, model::setFilter) }
                wordRows(rows, view) { openWord = it }
            }
        }
        item(key = "decks") {
            Decks(state, onImport = { picker.launch(MIME_TYPES) }, onDelete = { deleting = it })
        }
        item(key = "how") {
            SectionHeader(stringResource(R.string.lab_vocab_gaps_how))
            SectionCard(contentPadding = cardPadding) {
                Quiet(stringResource(R.string.lab_vocab_gaps_how_text))
                Spacer(Modifier.height(8.dp))
                Quiet(stringResource(R.string.lab_vocab_gaps_how_ambiguous))
            }
            SectionHeader(stringResource(R.string.lab_vocab_gaps_about_lists))
            SectionCard(contentPadding = cardPadding) { Quiet(stringResource(R.string.lab_vocab_gaps_attribution)) }
        }
    }

    val word = openWord?.let { key -> view?.deck?.entries?.firstOrNull { it.key == key } }
    if (word != null && view != null) WordDialog(word, view) { openWord = null }
    state.pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            languages = (listOfNotNull(state.language) + BundledDecks.languages + state.languages).distinct(),
            onImport = model::confirmImport,
            onCancel = model::cancelImport,
        )
    }
    deleting?.let { id ->
        DeleteDialog(onDelete = { deleting = null; model.delete(id) }, onCancel = { deleting = null })
    }
}

private val MIME_TYPES = arrayOf(
    "text/*", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
    "application/csv", "application/vnd.ms-excel", "application/octet-stream",
)
internal val cardPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

private fun filtered(view: VocabView, filter: WordFilter): List<DeckEntry> = when (filter) {
    WordFilter.All -> view.deck.entries
    WordFilter.Unused -> view.deck.entries.filter { it.key !in view.report.used }
    WordFilter.Used -> view.deck.entries.filter { it.key in view.report.used }
}

@Composable
private fun Loading() {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics { }, strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurface)
        Quiet(stringResource(R.string.lab_vocab_gaps_loading))
    }
}

@Composable
internal fun languageName(code: String): String {
    val locale = LocalResources.current.uiLocale
    return java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
        .ifEmpty { code }
}

@Composable
private fun deckName(deck: DeckChoice): String =
    if (deck.bundled) stringResource(R.string.lab_vocab_gaps_bundled_deck) else deck.name

@Composable
private fun Pickers(state: VocabGapsState, onLanguage: (String) -> Unit, onDeck: (String) -> Unit) {
    if (state.languages.size > 1) {
        SectionHeader(stringResource(R.string.lab_vocab_gaps_language))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.languages.forEach { language ->
                FilterChip(
                    selected = language == state.language,
                    onClick = { onLanguage(language) },
                    label = { Text(languageName(language)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    if (state.decks.size > 1) {
        SectionHeader(stringResource(R.string.lab_vocab_gaps_deck))
        SectionCard(Modifier.selectableGroup()) {
            state.decks.forEachIndexed { index, deck ->
                if (index > 0) RowDivider()
                ChoiceRow(
                    deckName(deck),
                    pluralStringResource(R.plurals.lab_vocab_gaps_deck_size, deck.size, deck.size),
                    selected = deck.id == state.deckId,
                    onClick = { onDeck(deck.id) },
                )
            }
        }
    }
}

@Composable
private fun Coverage(view: VocabView) {
    val report = view.report
    SectionHeader(stringResource(R.string.lab_vocab_gaps_coverage))
    SectionCard(contentPadding = cardPadding) {
        if (report.utterances == 0) {
            Text(stringResource(R.string.lab_vocab_gaps_empty_title, languageName(view.deck.language)),
                style = MaterialTheme.typography.titleSmall)
            Quiet(stringResource(R.string.lab_vocab_gaps_empty))
            Spacer(Modifier.height(16.dp))
        } else {
            report.headline?.let { headline ->
                Text(
                    stringResource(
                        when {
                            view.deck.formsOnly -> R.string.lab_vocab_gaps_level_forms
                            view.deck.bundled -> R.string.lab_vocab_gaps_level_common
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
        }
        report.milestones.forEachIndexed { index, milestone ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            MilestoneBar(milestone, isAll = index == report.milestones.lastIndex && milestone.top == view.deck.size)
        }
        if (report.utterances > 0) {
            Spacer(Modifier.height(16.dp))
            Quiet(pluralStringResource(R.plurals.lab_vocab_gaps_counted, report.utterances, report.utterances, report.words))
            if (report.tried > 0) Quiet(pluralStringResource(R.plurals.lab_vocab_gaps_tried, report.tried, report.tried))
            if (report.bySpelling > 0) {
                Quiet(pluralStringResource(R.plurals.lab_vocab_gaps_by_spelling, report.bySpelling, report.bySpelling))
            }
        }
        if (view.deck.formsOnly) {
            Spacer(Modifier.height(8.dp))
            Quiet(stringResource(R.string.lab_vocab_gaps_forms_only))
        }
    }
}

@Composable
private fun LevelSwitch(level: VocabLevel, onLevel: (VocabLevel) -> Unit) {
    SectionHeader(stringResource(R.string.lab_vocab_gaps_count_by))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VocabLevel.entries.forEach { option ->
            FilterChip(
                selected = option == level,
                onClick = { onLevel(option) },
                label = {
                    Text(stringResource(if (option == VocabLevel.Lexeme) R.string.lab_vocab_gaps_by_lexeme else R.string.lab_vocab_gaps_by_lemma))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    CardNote(stringResource(if (level == VocabLevel.Lexeme) R.string.lab_vocab_gaps_by_lexeme_note else R.string.lab_vocab_gaps_by_lemma_note))
}

@Composable
private fun MilestoneBar(milestone: Milestone, isAll: Boolean) {
    val label = stringResource(if (isAll) R.string.lab_vocab_gaps_all else R.string.lab_vocab_gaps_top, milestone.top)
    val description = stringResource(R.string.lab_vocab_gaps_bar_description, label, milestone.used, milestone.top)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.lab_vocab_gaps_used_of, milestone.used, milestone.top),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Linguize.colors.rowDivider),
        ) {
            val fraction = if (milestone.top == 0) 0f else milestone.used.toFloat() / milestone.top
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun Trend(report: VocabReport) {
    val resources = LocalResources.current
    val locale = resources.uiLocale
    val points = report.trend
    SectionHeader(stringResource(R.string.lab_vocab_gaps_over_time))
    SectionCard(contentPadding = cardPadding) {
        Quiet(stringResource(R.string.lab_vocab_gaps_trend_caption, report.trendTop))
        Spacer(Modifier.height(12.dp))
        val first = points.first()
        val last = points.last()
        val description = stringResource(
            R.string.lab_vocab_gaps_trend_description, report.trendTop,
            first.topUsed, monthName(first.month, locale), last.topUsed, monthName(last.month, locale),
        )
        TrendBars(points.map { it.topUsed }, Modifier.fillMaxWidth().height(72.dp).clearAndSetSemantics { contentDescription = description })
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Quiet(monthName(first.month, locale), Modifier.weight(1f))
            if (points.size > 1) Quiet(monthName(last.month, locale))
        }
        Spacer(Modifier.height(12.dp))
        points.takeLast(3).reversed().forEachIndexed { index, point ->
            if (index > 0) RowDivider()
            MonthRow(point, locale)
        }
        Spacer(Modifier.height(8.dp))
        Quiet(stringResource(R.string.lab_vocab_gaps_rarer_note))
    }
}

@Composable
private fun MonthRow(point: MonthPoint, locale: java.util.Locale) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthName(point.month, locale), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Quiet(
            stringResource(
                R.string.lab_vocab_gaps_month_summary,
                pluralStringResource(R.plurals.lab_vocab_gaps_month_words, point.distinct, point.distinct),
                point.rarerPercent,
            ),
        )
    }
}

/** One bar per month, scaled to the largest; decorative where the numbers are also in words. */
@Composable
private fun TrendBars(values: List<Int>, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.onSurface
    val empty = Linguize.colors.rowDivider
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1)
        val gap = 4.dp.toPx()
        val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtMost(32.dp.toPx())
        val radius = CornerRadius(width / 4, width / 4)
        values.forEachIndexed { index, value ->
            val height = if (value == 0) 2.dp.toPx() else size.height * value / max
            drawRoundRect(
                color = if (value == 0) empty else bar,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = radius,
            )
        }
    }
}

internal fun monthName(month: String, locale: java.util.Locale): String = runCatching {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMyyyy")
    java.time.YearMonth.parse(month).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}.getOrDefault(month)

@Composable
private fun TryNext(view: VocabView, onCopy: (String) -> Unit) {
    val groups = remember(view) { VocabSuggestions.next(view.deck, view.report) }
    val targets = remember(view) { VocabSuggestions.targets(view.deck, view.report) }
    SectionHeader(stringResource(R.string.lab_vocab_gaps_try_next))
    SectionCard(contentPadding = cardPadding) {
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

@Composable
private fun Filters(view: VocabView, filter: WordFilter, onFilter: (WordFilter) -> Unit) {
    val used = view.deck.entries.count { it.key in view.report.used }
    val counts = mapOf(
        WordFilter.All to view.deck.size,
        WordFilter.Unused to view.deck.size - used,
        WordFilter.Used to used,
    )
    SectionHeader(stringResource(R.string.lab_vocab_gaps_words))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WordFilter.entries.forEach { option ->
            val name = stringResource(
                when (option) {
                    WordFilter.All -> R.string.lab_vocab_gaps_filter_all
                    WordFilter.Unused -> R.string.lab_vocab_gaps_filter_unused
                    WordFilter.Used -> R.string.lab_vocab_gaps_filter_used
                },
            )
            FilterChip(
                selected = option == filter,
                onClick = { onFilter(option) },
                label = { Text(stringResource(R.string.lab_vocab_gaps_filter_count, name, counts.getValue(option))) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

private fun LazyListScope.wordRows(rows: List<DeckEntry>, view: VocabView, onOpen: (String) -> Unit) {
    if (rows.isEmpty()) {
        item(key = "no-words") {
            SectionCard(contentPadding = cardPadding) { Quiet(stringResource(R.string.lab_vocab_gaps_none_in_filter)) }
        }
        return
    }
    itemsIndexed(rows, key = { _, entry -> "word:${entry.key}" }) { index, entry ->
        Column(Modifier.cardSegment(first = index == 0, last = index == rows.lastIndex)) {
            if (index > 0) RowDivider()
            WordRow(entry, view.report.used[entry.key], onOpen)
        }
    }
}

@Composable
private fun WordRow(entry: DeckEntry, use: com.evanaronson.linguize.core.vocab.WordUse?, onOpen: (String) -> Unit) {
    val resources = LocalResources.current
    val status = if (use == null) stringResource(R.string.lab_vocab_gaps_not_yet)
    else pluralStringResource(R.plurals.lab_vocab_gaps_used_times, use.count, use.count)
    val last = use?.let { stringResource(R.string.lab_vocab_gaps_last_used, shortDate(it.lastAt, locale = resources.uiLocale)) }
    val clickLabel = stringResource(R.string.lab_vocab_gaps_open_word)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (use != null) Modifier.clickable(onClickLabel = clickLabel) { onOpen(entry.key) }
                else Modifier.semantics(mergeDescendants = true) { },
            )
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            entry.rank.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(entry.lemma, style = MaterialTheme.typography.bodyLarge)
            val summary = listOfNotNull(posLabel(entry.pos)?.let { stringResource(it) }, entry.gloss).joinToString(", ")
            if (summary.isNotEmpty()) Quiet(summary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (use == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Decks(state: VocabGapsState, onImport: () -> Unit, onDelete: (String) -> Unit) {
    SectionHeader(stringResource(R.string.lab_vocab_gaps_your_decks))
    SectionCard {
        if (state.imported.isEmpty()) {
            ListRow(stringResource(R.string.lab_vocab_gaps_no_decks), stringResource(R.string.lab_vocab_gaps_import_caption))
        }
        state.imported.forEachIndexed { index, deck ->
            if (index > 0) RowDivider()
            val description = stringResource(R.string.lab_vocab_gaps_delete_deck_description, deck.name)
            ListRow(
                deck.name,
                stringResource(
                    R.string.lab_vocab_gaps_deck_summary,
                    languageName(deck.language),
                    pluralStringResource(R.plurals.lab_vocab_gaps_deck_size, deck.size, deck.size),
                ),
            ) {
                LinguizeButton(
                    stringResource(R.string.lab_vocab_gaps_delete_deck), { onDelete(deck.id) }, ButtonKind.Destructive,
                    Modifier.semantics { contentDescription = description },
                )
            }
        }
        RowDivider()
        Column(Modifier.padding(vertical = 12.dp)) {
            LinguizeButton(stringResource(R.string.lab_vocab_gaps_import), onImport, ButtonKind.Tonal)
            state.importMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Quiet(importMessage(message))
            }
        }
    }
}

@Composable
private fun importMessage(message: ImportMessage): String = when (message) {
    is ImportMessage.Imported -> pluralStringResource(R.plurals.lab_vocab_gaps_imported, message.words, message.words)
    is ImportMessage.Failed -> stringResource(
        if (message.reason == DeckParse.Reason.Empty) R.string.lab_vocab_gaps_import_empty else R.string.lab_vocab_gaps_import_no_words,
    )
    ImportMessage.Unreadable -> stringResource(R.string.lab_vocab_gaps_import_unreadable)
    ImportMessage.TooLarge -> stringResource(R.string.lab_vocab_gaps_import_too_large)
}

/** The part of speech shown under a lexeme; null for a bare lemma. */
internal fun posLabel(token: String?): Int? = when (token?.let(Pos::of)) {
    Pos.Noun -> R.string.lab_vocab_gaps_pos_noun
    Pos.Verb -> R.string.lab_vocab_gaps_pos_verb
    Pos.Adjective -> R.string.lab_vocab_gaps_pos_adj
    Pos.Adverb -> R.string.lab_vocab_gaps_pos_adv
    Pos.Pronoun -> R.string.lab_vocab_gaps_pos_pron
    Pos.Determiner -> R.string.lab_vocab_gaps_pos_det
    Pos.Article -> R.string.lab_vocab_gaps_pos_art
    Pos.Preposition -> R.string.lab_vocab_gaps_pos_prep
    Pos.Conjunction -> R.string.lab_vocab_gaps_pos_conj
    Pos.Numeral -> R.string.lab_vocab_gaps_pos_num
    Pos.Interjection -> R.string.lab_vocab_gaps_pos_interj
    Pos.Other -> R.string.lab_vocab_gaps_pos_other
    Pos.Unknown, null -> null
}
