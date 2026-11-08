package com.evanaronson.linguize.ui.words

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.words.GrowthPoint
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.core.words.WordCounts
import com.evanaronson.linguize.core.words.WordEntry
import com.evanaronson.linguize.core.words.LexemeEntry
import com.evanaronson.linguize.core.words.LemmaEntry
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.core.corpus.Pos
import androidx.compose.foundation.selection.selectableGroup
import com.evanaronson.linguize.core.words.WordSort
import com.evanaronson.linguize.core.words.WordViews
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.theme.Linguize
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Your words: every word the writer typed in one language, with headline counts, growth
 * over time and a list to sort and search. Tap a word for the sentences it was used in.
 */
@Composable
fun WordsScreen(words: WordsViewModel, historyOn: Boolean?, onOpenWord: (language: String?, key: String, level: WordLevel) -> Unit) {
    val state by words.state.collectAsState()
    LaunchedEffect(words) { words.refresh() }
    WordsExportEffect(words)
    val index = state.index
    val shown = state.shown
    if (index == null || shown == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (index == null && state.loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                SectionCard(contentPadding = padding) {
                    Text(stringResource(R.string.lab_words_empty_title), style = MaterialTheme.typography.titleSmall)
                    Quiet(stringResource(R.string.lab_words_empty))
                }
                HowItWorks()
            }
            if (historyOn == false) CardNote(stringResource(R.string.history_off))
        }
        return
    }
    val locale = LocalResources.current.uiLocale
    val level = if (shown.morphology) state.level else WordLevel.Forms
    val all: List<WordCounts> = shown.rows(level)
    val rows = WordViews.arrange(all, state.sort, state.query, Locale.forLanguageTag(shown.language ?: locale.toLanguageTag()))
    val last = rows.lastIndex
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        if (index.languages.size > 1) item(key = "languages", contentType = "languages") {
            LanguageSwitch(index.languages.map { it.language }, shown.language, words::selectLanguage)
        }
        item(key = "stats", contentType = "stats") { Stats(shown) }
        item(key = "growth", contentType = "growth") { Growth(shown.growth) }
        item(key = "controls", contentType = "controls") {
            Controls(state.query, state.sort, level, shown.morphology, words::search, words::sortBy, words::countBy)
            if (level != WordLevel.Forms && shown.ambiguousForms > 0) {
                Quiet(stringResource(R.string.lab_words_ambiguous_note), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
        }
        if (rows.isEmpty()) {
            item(key = "none", contentType = "notice") {
                SectionCard(contentPadding = padding) {
                    Quiet(stringResource(if (state.sort == WordSort.Tried && state.query.isBlank()) R.string.lab_words_no_tried else R.string.lab_words_no_match))
                }
            }
        }
        itemsIndexed(rows, key = { _, row -> "w:" + row.key }, contentType = { _, _ -> "word" }) { position, row ->
            Column(Modifier.cardSegment(first = position == 0, last = position == last).padding(horizontal = 20.dp)) {
                if (position > 0) RowDivider()
                WordRow(row, shown.morphology, onClick = { onOpenWord(shown.language, row.key, level) })
            }
        }
        item(key = "footer", contentType = "footer") {
            Column {
                if (shown.names.isNotEmpty()) {
                    val listed = shown.names.take(MAX_NAMES).joinToString(", ") { it.written }
                    CardNote(pluralStringResource(R.plurals.lab_words_names, shown.names.size, shown.names.size, listed))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    LinguizeButton(stringResource(R.string.lab_words_export), words::exportCsv, ButtonKind.Text)
                }
                HowItWorks(shown.morphology)
                if (historyOn == false) CardNote(stringResource(R.string.history_off))
            }
        }
    }
}

private val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
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

@Composable
private fun LanguageSwitch(languages: List<String?>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        languages.forEach { code ->
            FilterChip(selected = code == selected, onClick = { onSelect(code) }, label = { Text(languageLabel(code)) })
        }
    }
}

@Composable
private fun Stats(words: LanguageWords) {
    SectionHeader(languageLabel(words.language))
    SectionCard(contentPadding = padding) {
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
    }
    if (words.newThisMonth > 0) CardNote(pluralStringResource(R.plurals.lab_words_new_this_month, words.newThisMonth, words.newThisMonth))
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    val locale = LocalResources.current.uiLocale
    val number = String.format(locale, "%,d", value)
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(number, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Quiet(label)
    }
}

/** Distinct words used right, day by day, as a filled step line from the first day to today. */
@Composable
private fun Growth(points: List<GrowthPoint>) {
    if (points.isEmpty()) return
    val locale = LocalResources.current.uiLocale
    val zone = ZoneId.systemDefault()
    val first = points.first()
    val lastPoint = points.last()
    val today = LocalDate.now(zone)
    fun dayLabel(day: LocalDate) = shortDate(day.atStartOfDay(zone).toInstant().toEpochMilli(), locale = locale)
    SectionHeader(stringResource(R.string.lab_words_growth))
    SectionCard(contentPadding = padding) {
        if (first.day >= today) {
            Quiet(stringResource(R.string.lab_words_growth_one_day, lastPoint.distinct, dayLabel(first.day)))
            return@SectionCard
        }
        val description = stringResource(
            R.string.lab_words_growth_description, first.distinct, dayLabel(first.day), lastPoint.distinct, dayLabel(lastPoint.day),
        )
        Text(String.format(locale, "%,d", lastPoint.distinct), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        GrowthChart(points, today, Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics { }) {
            Text(dayLabel(first.day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(dayLabel(today), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GrowthChart(points: List<GrowthPoint>, today: LocalDate, modifier: Modifier) {
    val line = Linguize.colors.proof
    val grid = Linguize.colors.rowDivider
    Canvas(modifier) {
        val startDay = points.first().day.toEpochDay()
        val endDay = maxOf(today.toEpochDay(), points.last().day.toEpochDay(), startDay + 1)
        val max = points.maxOf { it.distinct }.coerceAtLeast(1)
        fun x(day: Long) = (day - startDay).toFloat() / (endDay - startDay) * size.width
        fun y(value: Int) = size.height - value.toFloat() / max * (size.height - 4.dp.toPx())
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        val path = Path()
        var previous = 0
        path.moveTo(0f, y(0))
        points.forEach { point ->
            val px = x(point.day.toEpochDay())
            path.lineTo(px, y(previous))
            path.lineTo(px, y(point.distinct))
            previous = point.distinct
        }
        path.lineTo(size.width, y(previous))
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, line.copy(alpha = 0.12f))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun Controls(
    query: String,
    sort: WordSort,
    level: WordLevel,
    canGroup: Boolean,
    onSearch: (String) -> Unit,
    onSort: (WordSort) -> Unit,
    onLevel: (WordLevel) -> Unit,
) {
    SectionHeader(stringResource(R.string.lab_words_list))
    LinguizeTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.lab_words_search),
        accessibleLabel = stringResource(R.string.lab_words_search),
        singleLine = true,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WordSort.entries.forEach { option ->
            FilterChip(selected = option == sort, onClick = { onSort(option) }, label = { Text(sortLabel(option)) })
        }
    }
    // Lexemes and lemmas only mean something once the lemmatizer knows morphology.
    if (canGroup) {
        val countBy = stringResource(R.string.lab_words_levels)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)
                .semantics { contentDescription = countBy }.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WordLevel.entries.forEach { option ->
                FilterChip(selected = option == level, onClick = { onLevel(option) }, label = { Text(levelLabel(option)) })
            }
        }
    }
}

@Composable
private fun sortLabel(sort: WordSort): String = stringResource(
    when (sort) {
        WordSort.MostUsed -> R.string.lab_words_sort_most_used
        WordSort.Newest -> R.string.lab_words_sort_newest
        WordSort.Alphabetical -> R.string.lab_words_sort_az
        WordSort.Tried -> R.string.lab_words_sort_tried
    },
)

@Composable
private fun WordRow(row: WordCounts, morphology: Boolean, onClick: () -> Unit) {
    val usage = when {
        !row.known -> pluralStringResource(R.plurals.lab_words_row_tried, row.tried, row.tried)
        row.tried > 0 -> stringResource(
            R.string.lab_words_row_pair,
            pluralStringResource(R.plurals.lab_words_row_used, row.messages, row.messages),
            pluralStringResource(R.plurals.lab_words_row_fixed, row.tried, row.tried),
        )
        else -> pluralStringResource(R.plurals.lab_words_row_used, row.messages, row.messages)
    }
    // Under the usage: which lexemes a form could be, or which forms a lexeme or lemma has.
    val structure: String? = when (row) {
        is WordEntry -> if (morphology && row.ambiguous) {
            stringResource(R.string.lab_words_row_could_be, row.lexemes.map { lexemeTitle(it) }.joinToString(", "))
        } else null
        is LexemeEntry -> formsLine(row.forms, row.ambiguousForms)
        is LemmaEntry -> {
            val forms = row.forms.joinToString(", ")
            val parts = row.lexemes.filter { it.pos != Pos.Unknown }.map { stringResource(it.pos.labelResource) }
            if (parts.isEmpty()) stringResource(R.string.lab_words_row_forms, forms)
            else stringResource(R.string.lab_words_row_lemma, parts.joinToString(", "), forms)
        }
        else -> null
    }
    val title = if (row is LexemeEntry) lexemeTitle(row.lexeme) else row.label
    ListRow(
        title,
        structure?.let { stringResource(R.string.lab_words_row_pair, usage, it) } ?: usage,
        onClick = onClick,
        onClickLabel = stringResource(R.string.lab_words_open_word),
    ) {
        Text(pluralStringResource(R.plurals.lab_words_uses, row.uses, row.uses), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun HowItWorks(morphology: Boolean = false) {
    SectionHeader(stringResource(R.string.lab_words_how_header))
    SectionCard(contentPadding = padding) {
        Quiet(stringResource(R.string.lab_words_how))
        if (morphology) {
            Spacer(Modifier.height(8.dp))
            Quiet(stringResource(R.string.lab_words_how_levels))
            Spacer(Modifier.height(8.dp))
            Quiet(stringResource(R.string.lab_words_how_ambiguous))
        }
        Spacer(Modifier.height(8.dp))
        Quiet(stringResource(R.string.lab_words_how_tried))
        Spacer(Modifier.height(8.dp))
        Quiet(stringResource(R.string.lab_words_how_delete))
    }
}

/** Hands a finished CSV to the share sheet, once. */
@Composable
private fun WordsExportEffect(words: WordsViewModel) {
    val context = LocalContext.current
    val export by words.export.collectAsState()
    LaunchedEffect(export) {
        export?.let {
            share(context, it)
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
