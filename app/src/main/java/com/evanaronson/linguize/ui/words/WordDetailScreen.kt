package com.evanaronson.linguize.ui.words

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.words.DeckUse
import com.evanaronson.linguize.core.words.LemmaEntry
import com.evanaronson.linguize.core.words.WordList
import com.evanaronson.linguize.core.words.LexemeEntry
import com.evanaronson.linguize.core.words.WordLevel
import com.evanaronson.linguize.core.words.WordContext
import com.evanaronson.linguize.core.words.WordCounts
import com.evanaronson.linguize.core.words.WordEntry
import com.evanaronson.linguize.core.words.WordViews
import com.evanaronson.linguize.history.corpus.CheckUtterances
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * One row of Words: its deck rank and part of speech, the writer's counts, and the sentences
 * it appears in, newest first. A sentence from a saved check opens that check. [key] is the
 * row's key in the list ("d:" for a deck row, "w:" for a word of the writer's).
 */
@Composable
fun WordDetailScreen(
    words: WordsViewModel,
    language: String?,
    key: String,
    /** The `WordLevel` token. */
    level: String,
    onOpenCheck: (sessionId: String) -> Unit,
) {
    val state by words.state.collectAsState()
    val wanted = WordLevel.read(level) ?: WordLevel.Forms
    LaunchedEffect(words, language, wanted) {
        // After the process was gone, the list is rebuilt on this row's language and level.
        if (state.shownLanguage != language) words.selectLanguage(language)
        if (state.shownLevel != wanted) words.countBy(wanted)
        words.refresh()
    }
    val shown = state.shown?.takeIf { it.language == language }
    val row = remember(shown, state.coverage, state.shownLevel) {
        shown?.let {
            val rows = WordList.rows(it, state.coverage, state.shownLevel)
            // Pages saved before the merge named a word without "d:" or "w:".
            listOf(key, "d:$key", "w:$key").firstNotNullOfOrNull { candidate -> rows.firstOrNull { row -> row.key == candidate } }
        }
    }
    ScreenColumn {
        if (shown == null || row == null) {
            if (state.index != null && !state.loading) CardNote(stringResource(R.string.lab_words_gone))
            return@ScreenColumn
        }
        Spacer(Modifier.height(8.dp))
        val locale = LocalResources.current.uiLocale
        val date = { at: Long -> shortDate(at, locale = locale) }
        val entry = row.counts
        SectionCard {
            val coverage = state.coverage
            if (row.rank != null && coverage != null) {
                val deck = state.decks.firstOrNull { it.id == state.deckId }
                Fact(stringResource(R.string.lab_words_detail_rank, deck?.let { deckName(it) } ?: coverage.deck.name), row.rank.toString())
                RowDivider()
            }
            val about = listOfNotNull(row.pos?.let { stringResource(it.labelResource) }, row.gloss).joinToString(", ")
            if (about.isNotEmpty()) {
                ListRow(row.label, about)
                RowDivider()
            }
            if (entry == null) {
                ListRow(stringResource(R.string.lab_words_detail_not_used))
                return@SectionCard
            }
            Fact(stringResource(R.string.lab_words_detail_right), entry.right.toString())
            if (entry.tried > 0) { RowDivider(); Fact(stringResource(R.string.lab_words_detail_tried), entry.tried.toString()) }
            RowDivider(); Fact(stringResource(R.string.lab_words_detail_messages), entry.messages.toString())
            RowDivider(); Fact(stringResource(R.string.lab_words_detail_first), date(entry.firstAt))
            RowDivider(); Fact(stringResource(R.string.lab_words_detail_last), date(entry.lastAt))
            when (entry) {
                is WordEntry -> if (shown.morphology) {
                    RowDivider()
                    ListRow(stringResource(R.string.lab_words_detail_lexemes), entry.lexemes.map { lexemeTitle(it) }.joinToString(", "))
                }
                is LexemeEntry -> {
                    RowDivider()
                    ListRow(stringResource(R.string.lab_words_detail_forms), entry.forms.joinToString(", "))
                    if (entry.ambiguousForms.isNotEmpty()) {
                        RowDivider()
                        ListRow(stringResource(R.string.lab_words_detail_ambiguous), entry.ambiguousForms.joinToString(", "))
                    }
                }
                is LemmaEntry -> {
                    if (shown.morphology) {
                        RowDivider()
                        ListRow(stringResource(R.string.lab_words_detail_lexemes_under), entry.lexemes.map { lexemeTitle(it) }.joinToString(", "))
                    }
                    RowDivider()
                    ListRow(stringResource(R.string.lab_words_detail_forms), entry.forms.joinToString(", "))
                }
                is DeckUse -> {
                    if (entry.forms.isNotEmpty()) {
                        RowDivider()
                        ListRow(stringResource(R.string.lab_words_detail_forms), entry.forms.joinToString(", "))
                    }
                    if (entry.bySpelling) {
                        RowDivider()
                        ListRow(stringResource(R.string.lab_vocab_gaps_matched_by_spelling))
                    }
                }
            }
        }
        if (entry == null) return@ScreenColumn
        val ambiguous = entry is LexemeEntry && entry.ambiguousForms.isNotEmpty() ||
            entry is WordEntry && entry.ambiguous && shown.morphology ||
            entry is DeckUse && entry.forms.any { form -> (state.coverage?.formRows?.get(form)?.size ?: 0) > 1 }
        if (ambiguous) CardNote(stringResource(R.string.lab_words_how_ambiguous))
        SectionHeader(stringResource(R.string.lab_words_contexts))
        val contexts = entry.recent.mapNotNull { WordViews.context(it, shown.texts) }
        SectionCard {
            contexts.forEachIndexed { index, context ->
                if (index > 0) RowDivider()
                ContextRow(context, date(context.use.at), onOpenCheck)
            }
        }
        if (entry.uses > contexts.size) {
            CardNote(pluralStringResource(R.plurals.lab_words_contexts_newest, contexts.size, contexts.size, entry.uses))
        }
    }
}

/** The navigation bar's title for a row key: the word without its prefix or part of speech. */
fun wordTitle(key: String): String = key.removePrefix("d:").removePrefix("w:").let { if ('/' in it) it.substringBeforeLast('/') else it }

@Composable
private fun Fact(label: String, value: String) {
    ListRow(label, trailing = {
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    })
}

@Composable
private fun ContextRow(context: WordContext, date: String, onOpenCheck: (String) -> Unit) {
    val session = CheckUtterances.sessionOf(context.use.utteranceId)
    val source = when (context.use.source) {
        "check" -> stringResource(R.string.lab_words_source_check)
        "chat" -> stringResource(R.string.lab_words_source_chat)
        else -> context.use.source
    }
    val caption = stringResource(if (context.use.tried) R.string.lab_words_context_tried else R.string.lab_words_context_caption, date, source)
    val proof = Linguize.colors.proof
    val sentence = buildAnnotatedString {
        if (context.cutBefore) append("… ")
        append(context.sentence.substring(0, context.start))
        val style = if (context.use.tried) {
            SpanStyle(fontWeight = FontWeight.SemiBold, color = proof, textDecoration = TextDecoration.LineThrough)
        } else {
            SpanStyle(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
        }
        withStyle(style) { append(context.sentence.substring(context.start, context.end)) }
        append(context.sentence.substring(context.end))
        if (context.cutAfter) append(" …")
    }
    val openLabel = stringResource(R.string.lab_words_open_check)
    Column(
        Modifier.fillMaxWidth()
            .then(if (session != null) Modifier.clickable(onClickLabel = openLabel) { onOpenCheck(session) } else Modifier)
            .heightIn(min = 56.dp)
            .padding(PaddingValues(vertical = 12.dp))
            .semantics(mergeDescendants = true) { },
    ) {
        Text(sentence, style = MaterialTheme.typography.bodyLarge)
        Quiet(caption)
    }
}
