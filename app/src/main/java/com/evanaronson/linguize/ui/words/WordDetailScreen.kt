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
import com.evanaronson.linguize.core.words.LemmaEntry
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
 * One word (or dictionary form, when [lemma]): its counts and the writer's own sentences it
 * appears in, newest first. A sentence from a saved check opens that check.
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
    LaunchedEffect(words) { words.refresh() }
    val shown = state.index?.language(language)
    val entry: WordCounts? = WordLevel.read(level)?.let { shown?.entry(it, key) }
    ScreenColumn {
        if (shown == null || entry == null) {
            if (state.index != null) CardNote(stringResource(R.string.lab_words_gone))
            return@ScreenColumn
        }
        Spacer(Modifier.height(8.dp))
        val locale = LocalResources.current.uiLocale
        val date = { at: Long -> shortDate(at, locale = locale) }
        SectionCard {
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
            }
        }
        if (entry is LexemeEntry && entry.ambiguousForms.isNotEmpty() || entry is WordEntry && entry.ambiguous && shown.morphology) {
            CardNote(stringResource(R.string.lab_words_how_ambiguous))
        }
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
