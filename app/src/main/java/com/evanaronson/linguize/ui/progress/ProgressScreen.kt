package com.evanaronson.linguize.ui.progress

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.Sparkline
import com.evanaronson.linguize.ui.patterns.patternsSummary
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.words.WordsViewModel
import com.evanaronson.linguize.ui.words.languageLabel
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.language.scopeLanguageName
import com.evanaronson.linguize.ui.phrasebook.inLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A page Progress opens. Each shows the app's current language. */
enum class ProgressView { Practice, Words, Conjugations, Patterns, Phrasebook, Time }

/**
 * Progress, one of the four destinations: how the writer is doing in the app's current
 * [language] (Lab, Language scope), all read from History. Practice is the thing to do; the
 * rest are views. Every section, and every page it opens, shows only [language]; the switch
 * is the app's, in the top bar. Rows that only count are cheap; nothing here asks a model.
 */
@Composable
fun ProgressScreen(
    practice: PracticeViewModel,
    patterns: PatternsViewModel,
    words: WordsViewModel,
    conjugations: ConjugationsViewModel,
    language: String?,
    onOpen: (ProgressView) -> Unit,
) {
    val practiceState by practice.state.collectAsState()
    val patternsState by patterns.state.collectAsState()
    val wordsState by words.state.collectAsState()
    val conjugationsState by conjugations.state.collectAsState()
    // Time passes while Progress is away; due items are counted against the clock.
    LaunchedEffect(practice) { practice.tick() }
    LaunchedEffect(conjugations) { conjugations.refresh() }

    val wordCounts = wordsState.index?.languages.orEmpty().map { it.language to it.distinctWords }
    fun open(view: ProgressView) = onOpen(view)

    ScreenColumn {
        language?.let { CardNote(stringResource(R.string.lab_language_scope_progress_note, scopeLanguageName(it))) }
        SectionHeader(stringResource(R.string.lab_practice_title))
        val due = practiceState.due
        ListCard({
            ListRow(
                title = if (due > 0) pluralStringResource(R.plurals.lab_practice_due, due, due) else stringResource(R.string.lab_practice_nothing_due),
                summary = stringResource(R.string.lab_practice_due_summary),
                onClick = { open(ProgressView.Practice) },
                onClickLabel = stringResource(R.string.lab_practice_open),
            )
        })

        SectionHeader(stringResource(R.string.lab_home_ia_your_writing))
        val wordCount = wordCounts.firstOrNull { it.first == language }?.second ?: 0
        val report = language?.let { conjugationsState.reports[it] }?.takeUnless { it.isEmpty }
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_words_title),
                    if (wordCount > 0 && language != null) {
                        pluralStringResource(R.plurals.lab_words_home_summary, wordCount, wordCount, languageLabel(language))
                    } else stringResource(R.string.lab_words_history_summary),
                    onClick = { open(ProgressView.Words) },
                    onClickLabel = stringResource(R.string.lab_words_open),
                )
            },
            {
                ListRow(
                    stringResource(R.string.lab_conjugations_title),
                    report?.let {
                        val total = it.gridCells.size
                        pluralStringResource(R.plurals.lab_conjugations_cells_used, total, it.usedGridCells, total)
                    } ?: stringResource(R.string.lab_conjugations_home_summary),
                    onClick = { open(ProgressView.Conjugations) },
                    onClickLabel = stringResource(R.string.lab_conjugations_open),
                )
            },
        )

        SectionHeader(stringResource(R.string.lab_home_ia_fixes_and_phrases))
        val patternReport = patternsState.snapshot?.report?.takeIf { it.worthShowing }
        val phrases = rememberPhraseCount(language)
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_patterns_title),
                    patternReport?.let { patternsSummary(it) } ?: stringResource(R.string.lab_patterns_history_summary),
                    onClick = { open(ProgressView.Patterns) },
                    onClickLabel = stringResource(R.string.lab_patterns_open),
                ) {
                    patternReport?.patterns?.firstOrNull()?.let {
                        Sparkline(it.buckets, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { })
                    }
                }
            },
            {
                ListRow(
                    stringResource(R.string.lab_phrasebook_title),
                    when (phrases) {
                        null -> null
                        0 -> stringResource(R.string.lab_home_ia_phrasebook_summary)
                        else -> pluralStringResource(R.plurals.lab_phrasebook_count, phrases, phrases)
                    },
                    onClick = { open(ProgressView.Phrasebook) },
                )
            },
        )

        com.evanaronson.linguize.ui.time.TimeSpentSection(language) { open(ProgressView.Time) }
    }
}

/** How many phrases are saved in [language]; null until the file has been read. */
@Composable
private fun rememberPhraseCount(language: String?): Int? {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    return phrases?.let { inLanguage(it, language).size }
}
