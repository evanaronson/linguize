package com.evanaronson.linguize.ui.progress

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.conjugations.ConjugationReport
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel
import com.evanaronson.linguize.ui.conjugations.CONJUGATION_LANGUAGES
import com.evanaronson.linguize.ui.language.LanguageIcon
import com.evanaronson.linguize.ui.language.LanguageOption
import com.evanaronson.linguize.ui.language.brandName
import com.evanaronson.linguize.ui.language.scopeLanguageName
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.Sparkline
import com.evanaronson.linguize.ui.patterns.patternsSummary
import com.evanaronson.linguize.ui.phrasebook.inLanguage
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.words.WordsViewModel
import com.evanaronson.linguize.ui.words.languageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A page Progress opens. Each shows the app's current language, or the block's language under Linguize. */
enum class ProgressView { Practice, Words, Conjugations, Patterns, Phrasebook, Time }

/**
 * Lab, Consistency: the languages Progress shows a block for. With a language chosen, just
 * that one (and no heading: the selector says it). Under Linguize (Auto, null), one block per
 * language in use ([LanguageScope.inUse]) of the languages [found] in the writer's data.
 */
internal fun progressLanguages(current: String?, found: Collection<String?>, mine: List<String> = LanguageScope.DEFAULT_LANGUAGES): List<String> =
    if (current != null) listOf(current) else LanguageScope.inUse(found, mine)

/**
 * Progress, one of the four destinations: how the writer is doing, all read from History.
 * Practice is the thing to do; the rest are views. Nothing here asks a model.
 *
 * With a language chosen at the top ([language]), every section, and every page it opens,
 * shows only that language. Under Linguize (Auto, null) the sections that are inherently per
 * language (Practice, Words, Your conjugations, Your patterns, Phrasebook) are stacked once
 * per language in use, each headed by its brand name and icon, and [onOpen] gets that block's
 * language so its page opens in it. Time adds up across languages, once; its page breaks it down.
 */
@Composable
fun ProgressScreen(
    practice: PracticeViewModel,
    patterns: PatternsViewModel,
    words: WordsViewModel,
    conjugations: ConjugationsViewModel,
    language: String?,
    /** The selector's languages, for each block's flag (Castilianize's follows the variety). */
    languageOptions: StateFlow<List<LanguageOption>>,
    /** Opens a page; the language is the block's under Linguize, else null (the app's language applies). */
    onOpen: (ProgressView, String?) -> Unit,
) {
    val practiceState by practice.state.collectAsState()
    val patternsState by patterns.state.collectAsState()
    val wordsState by words.state.collectAsState()
    val conjugationsState by conjugations.state.collectAsState()
    val options by languageOptions.collectAsState()
    // Time passes while Progress is away; due items are counted against the clock.
    LaunchedEffect(practice) { practice.tick() }
    LaunchedEffect(conjugations) { conjugations.refresh() }

    val wordCounts = wordsState.index?.languages.orEmpty().associate { it.language to it.distinctWords }
    val phrases = rememberPhrases()
    val found = practiceState.items.orEmpty().map { it.language } +
        wordCounts.filterValues { it > 0 }.keys +
        phrases.orEmpty().map { it.language } +
        conjugationsState.reports.filterValues { !it.isEmpty }.keys
    val blocks = progressLanguages(language, found, options.map { it.code })

    ScreenColumn {
        if (language != null) {
            CardNote(stringResource(R.string.lab_language_scope_progress_note, scopeLanguageName(language)))
        } else {
            CardNote(stringResource(R.string.lab_consistency_progress_auto_note))
        }
        blocks.forEach { block ->
            val auto = language == null
            if (auto) BlockHeading(options.firstOrNull { it.code == block } ?: LanguageOption(block))
            fun open(view: ProgressView) = onOpen(view, if (auto) block else null)
            LanguageBlock(
                language = block,
                labelled = auto,
                due = if (auto) practiceState.dueIn(block) else practiceState.due,
                wordCount = wordCounts[block] ?: 0,
                conjugations = conjugationsState.reports[block]?.takeUnless { it.isEmpty },
                // Lab, language-generic: the grid exists for Spanish and Catalan only; another language has no row.
                showConjugations = block in CONJUGATION_LANGUAGES,
                // The report is for the app's language; under Linguize it covers every language, so a block doesn't show it.
                patternSummary = if (auto) null else patternsState.snapshot?.report?.takeIf { it.worthShowing }?.let { patternsSummary(it) },
                patternSparkline = if (auto) null else patternsState.snapshot?.report?.takeIf { it.worthShowing }?.patterns?.firstOrNull()?.buckets,
                phraseCount = phrases?.let { inLanguage(it, block).size },
                onOpen = ::open,
            )
        }
        com.evanaronson.linguize.ui.time.TimeSpentSection(language) { onOpen(ProgressView.Time, null) }
    }
}

/** A block's heading under Linguize: the language's icon and brand name ("Catalanize"). */
@Composable
private fun BlockHeading(option: LanguageOption) {
    val name = brandName(option.code)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 20.dp, top = 18.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageIcon(option, Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LanguageBlock(
    language: String,
    /** Under Linguize: the rows say which language they open ("Practice in Catalan"). */
    labelled: Boolean,
    due: Int,
    wordCount: Int,
    conjugations: ConjugationReport?,
    showConjugations: Boolean,
    patternSummary: String?,
    patternSparkline: List<Int>?,
    phraseCount: Int?,
    onOpen: (ProgressView) -> Unit,
) {
    val name = scopeLanguageName(language)
    @Composable
    fun openLabel(resource: Int): String =
        if (labelled) stringResource(R.string.lab_consistency_progress_open_in, stringResource(resource), name) else stringResource(resource)

    SectionHeader(stringResource(R.string.lab_practice_title))
    ListCard({
        ListRow(
            title = if (due > 0) pluralStringResource(R.plurals.lab_practice_due, due, due) else stringResource(R.string.lab_practice_nothing_due),
            summary = stringResource(R.string.lab_practice_due_summary),
            onClick = { onOpen(ProgressView.Practice) },
            onClickLabel = openLabel(R.string.lab_practice_open),
        )
    })

    SectionHeader(stringResource(R.string.lab_home_ia_your_writing))
    ListCard(
        {
            ListRow(
                stringResource(R.string.lab_words_title),
                if (wordCount > 0) {
                    pluralStringResource(R.plurals.lab_words_home_summary, wordCount, wordCount, languageLabel(language))
                } else stringResource(R.string.lab_words_history_summary),
                onClick = { onOpen(ProgressView.Words) },
                onClickLabel = openLabel(R.string.lab_words_open),
            )
        },
        if (showConjugations) {
            {
                ListRow(
                    stringResource(R.string.lab_conjugations_title),
                    conjugations?.let {
                        val total = it.gridCells.size
                        pluralStringResource(R.plurals.lab_conjugations_cells_used, total, it.usedGridCells, total)
                    } ?: stringResource(R.string.lab_conjugations_home_summary),
                    onClick = { onOpen(ProgressView.Conjugations) },
                    onClickLabel = openLabel(R.string.lab_conjugations_open),
                )
            }
        } else null,
    )

    SectionHeader(stringResource(R.string.lab_home_ia_fixes_and_phrases))
    ListCard(
        {
            ListRow(
                stringResource(R.string.lab_patterns_title),
                patternSummary ?: stringResource(R.string.lab_patterns_history_summary),
                onClick = { onOpen(ProgressView.Patterns) },
                onClickLabel = openLabel(R.string.lab_patterns_open),
            ) {
                patternSparkline?.let { Sparkline(it, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { }) }
            }
        },
        {
            ListRow(
                stringResource(R.string.lab_phrasebook_title),
                when (phraseCount) {
                    null -> null
                    0 -> stringResource(R.string.lab_home_ia_phrasebook_summary)
                    else -> pluralStringResource(R.plurals.lab_phrasebook_count, phraseCount, phraseCount)
                },
                onClick = { onOpen(ProgressView.Phrasebook) },
            )
        },
    )
}

/** Every saved phrase; null until the file has been read. */
@Composable
private fun rememberPhrases(): List<Phrase>? {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    return phrases
}
