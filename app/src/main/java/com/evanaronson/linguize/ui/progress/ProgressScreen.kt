package com.evanaronson.linguize.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel
import com.evanaronson.linguize.ui.conjugations.defaultLanguage
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.Sparkline
import com.evanaronson.linguize.ui.patterns.patternsSummary
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.words.WordsViewModel
import com.evanaronson.linguize.ui.words.languageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A page Progress opens. The per-language ones open on the language shown on the switch. */
enum class ProgressView { Practice, Words, Conjugations, Patterns, Phrasebook, Time }

/**
 * Progress, one of the four destinations: how the writer is doing, all read from History.
 * Practice is the thing to do; the rest are views. Words and Your conjugations are per
 * language and share one switch; Your patterns and the Phrasebook
 * cover every language. Rows that only count are cheap; nothing here asks a model.
 */
@Composable
fun ProgressScreen(
    practice: PracticeViewModel,
    patterns: PatternsViewModel,
    words: WordsViewModel,
    conjugations: ConjugationsViewModel,
    onOpen: (ProgressView) -> Unit,
) {
    val practiceState by practice.state.collectAsState()
    val patternsState by patterns.state.collectAsState()
    val wordsState by words.state.collectAsState()
    val conjugationsState by conjugations.state.collectAsState()
    // Time passes while Progress is away; due items are counted against the clock.
    LaunchedEffect(practice) { practice.tick() }
    LaunchedEffect(conjugations) { conjugations.refresh() }

    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val wordCounts = wordsState.index?.languages.orEmpty().map { it.language to it.distinctWords }
    val language = progressLanguage(chosen, wordCounts, defaultLanguage(conjugationsState.reports))
    fun open(view: ProgressView) {
        when (view) {
            ProgressView.Words -> words.selectLanguage(language)
            ProgressView.Conjugations -> conjugations.select(language)
            else -> Unit
        }
        onOpen(view)
    }

    ScreenColumn {
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
        LanguageSwitch(language) { chosen = it }
        val wordCount = wordCounts.firstOrNull { it.first == language }?.second ?: 0
        val report = conjugationsState.reports[language]?.takeUnless { it.isEmpty }
        ListCard(
            {
                ListRow(
                    stringResource(R.string.lab_words_title),
                    if (wordCount > 0) {
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
        val phrases = rememberPhraseCount()
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

        com.evanaronson.linguize.ui.time.TimeSpentSection { open(ProgressView.Time) }
    }
}

/** Catalan or Spanish, for the per-language views. */
@Composable
private fun LanguageSwitch(current: String, onSelect: (String) -> Unit) {
    val label = stringResource(R.string.lab_home_ia_language)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).selectableGroup().semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PROGRESS_LANGUAGES.forEach { code ->
            val isSelected = code == current
            LinguizeButton(
                text = Language.forCode(code)?.displayName() ?: code,
                onClick = { onSelect(code) },
                kind = if (isSelected) ButtonKind.Filled else ButtonKind.Outlined,
                modifier = Modifier.weight(1f).semantics { selected = isSelected },
            )
        }
    }
}

/** How many phrases are saved; null until the file has been read. */
@Composable
private fun rememberPhraseCount(): Int? {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    return phrases?.size
}
