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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.conjugations.CONJUGATION_LANGUAGES
import com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel
import com.evanaronson.linguize.ui.language.LanguageIcon
import com.evanaronson.linguize.ui.language.LanguageOption
import com.evanaronson.linguize.ui.language.brandName
import com.evanaronson.linguize.ui.language.scopeLanguageName
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.Sparkline
import com.evanaronson.linguize.ui.patterns.patternsSummary
import com.evanaronson.linguize.ui.phrasebook.inLanguage
import com.evanaronson.linguize.ui.practice.PracticeState
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.practice.nextPracticeLine
import com.evanaronson.linguize.ui.time.TimeHeadline
import com.evanaronson.linguize.ui.words.WordsViewModel
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
internal fun progressLanguages(current: String?, found: Collection<String?>): List<String> =
    if (current != null) listOf(current) else LanguageScope.inUse(found)

/**
 * Lab, Progress IA: the tab's sections, top to bottom. One headline for the whole tab (this
 * week's time), then the one thing to do (Practice, a row per language), then the views of
 * the writer's own writing (a card per language, headed by its brand name under Linguize),
 * then what the writer kept (the Phrasebook, which is a collection, not a view).
 */
internal sealed interface ProgressSection {
    /** This week's time and the last weeks: how the writer is doing, at a glance. */
    data object Headline : ProgressSection

    /** Practice, one row per language; under Linguize each row names its language. */
    data class Practice(val languages: List<String>, val labelled: Boolean) : ProgressSection

    /** A language's views of the writer's writing; [labelled] under Linguize. */
    data class Views(val language: String, val labelled: Boolean, val views: List<ProgressView>) : ProgressSection

    /** The Phrasebook, once for the tab: its page groups phrases by language itself. */
    data object Saved : ProgressSection
}

/** The views a language's card lists. Your conjugations only where morphology exists. */
internal fun progressViews(language: String): List<ProgressView> = listOfNotNull(
    ProgressView.Words,
    ProgressView.Conjugations.takeIf { language in CONJUGATION_LANGUAGES },
    ProgressView.Patterns,
)

/** The tab, top to bottom, for [current] (null is Linguize) and the languages [found] in the data. */
internal fun progressLayout(current: String?, found: Collection<String?>): List<ProgressSection> {
    val blocks = progressLanguages(current, found)
    val labelled = current == null
    return buildList {
        add(ProgressSection.Headline)
        add(ProgressSection.Practice(blocks, labelled))
        blocks.forEach { add(ProgressSection.Views(it, labelled, progressViews(it))) }
        add(ProgressSection.Saved)
    }
}

/**
 * Progress, one of the four destinations: how the writer is doing, all read from History.
 * Nothing here asks a model. See [progressLayout] for the order.
 *
 * With a language chosen at the top ([language]), every section, and every page it opens,
 * shows only that language. Under Linguize (Auto, null) Practice has a row per language in
 * use and the views a card per language, each headed by its brand name and icon, and [onOpen]
 * gets that language so its page opens in it. Time and the Phrasebook cover every language
 * once; their pages break them down.
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
    /** Opens a page; the language is a block's or row's under Linguize, else null (the app's language applies). */
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

    val wordsByLanguage = wordsState.index?.languages.orEmpty().associateBy { it.language }
    val phrases = rememberPhrases()
    val found = practiceState.items.orEmpty().map { it.language } +
        wordsByLanguage.filterValues { it.distinctWords > 0 }.keys +
        phrases.orEmpty().map { it.language } +
        conjugationsState.reports.filterValues { !it.isEmpty }.keys
    val pattern = if (language == null) null else patternsState.snapshot?.report?.takeIf { it.worthShowing }

    ScreenColumn {
        progressLayout(language, found).forEach { section ->
            when (section) {
                ProgressSection.Headline -> TimeHeadline(language) { onOpen(ProgressView.Time, null) }
                is ProgressSection.Practice -> {
                    SectionHeader(stringResource(R.string.lab_practice_title))
                    ListCard(*section.languages.map { block ->
                        @Composable {
                            PracticeRow(practiceState, block, section.labelled) {
                                onOpen(ProgressView.Practice, if (section.labelled) block else null)
                            }
                        }
                    }.toTypedArray())
                }
                is ProgressSection.Views -> {
                    if (section.labelled) BlockHeading(options.firstOrNull { it.code == section.language } ?: LanguageOption(section.language))
                    else SectionHeader(stringResource(R.string.lab_home_ia_your_writing))
                    ViewsCard(
                        section,
                        words = wordsByLanguage[section.language],
                        conjugations = conjugationsState.reports[section.language]?.takeUnless { it.isEmpty }?.let { it.usedGridCells to it.gridCells.size },
                        patternSummary = pattern?.let { patternsSummary(it) },
                        patternSparkline = pattern?.patterns?.firstOrNull()?.buckets,
                    ) { view -> onOpen(view, if (section.labelled) section.language else null) }
                }
                ProgressSection.Saved -> {
                    SectionHeader(stringResource(R.string.lab_ia_progress_saved))
                    val count = phrases?.let { inLanguage(it, language).size }
                    ListCard({
                        ListRow(
                            stringResource(R.string.lab_phrasebook_title),
                            when (count) {
                                null -> null
                                0 -> stringResource(R.string.lab_home_ia_phrasebook_summary)
                                else -> pluralStringResource(R.plurals.lab_phrasebook_count, count, count)
                            },
                            onClick = { onOpen(ProgressView.Phrasebook, null) },
                        )
                    })
                }
            }
        }
    }
}

/**
 * Practice: what's due and the one button that starts it. With nothing due there is nothing
 * to open, so the row says when the next practice is instead. Under Linguize ([labelled]) the
 * row is the language and the count is its summary.
 */
@Composable
private fun PracticeRow(state: PracticeState, language: String, labelled: Boolean, onStart: () -> Unit) {
    val items = state.items
    val due = if (labelled) state.dueIn(language) else state.due
    val any = if (labelled) items.orEmpty().any { it.language == language } else !state.scoped.isNullOrEmpty()
    val next = nextPracticeLine(if (labelled) state.nextDueIn(language) else state.nextDue, state.today)
    val title = when {
        labelled -> scopeLanguageName(language)
        items == null -> stringResource(R.string.lab_practice_title)
        due > 0 -> pluralStringResource(R.plurals.lab_practice_due, due, due)
        !any -> stringResource(R.string.lab_ia_progress_practice_none)
        else -> stringResource(R.string.lab_practice_nothing_due)
    }
    val summary = when {
        items == null -> null
        labelled && due > 0 -> pluralStringResource(R.plurals.lab_ia_progress_practice_due_in, due, due)
        due > 0 -> stringResource(R.string.lab_practice_due_summary)
        labelled && !any -> stringResource(R.string.lab_ia_progress_practice_none)
        !any -> stringResource(R.string.lab_ia_progress_practice_none_hint)
        labelled -> next ?: stringResource(R.string.lab_practice_nothing_due)
        else -> next ?: stringResource(R.string.lab_practice_nothing_due_hint)
    }
    ListRow(title, summary) {
        if (due > 0) {
            val label = if (labelled) stringResource(R.string.lab_ia_progress_practice_start_in, scopeLanguageName(language))
            else stringResource(R.string.lab_practice_open)
            LinguizeButton(onStart, ButtonKind.Filled, Modifier.semantics { contentDescription = label }) {
                Text(stringResource(R.string.lab_practice_open), Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/** A language's views: Words, Your conjugations (Spanish and Catalan), Your patterns. */
@Composable
private fun ViewsCard(
    section: ProgressSection.Views,
    words: LanguageWords?,
    /** Forms used and forms in the grid, once counted. */
    conjugations: Pair<Int, Int>?,
    patternSummary: String?,
    patternSparkline: List<Int>?,
    onOpen: (ProgressView) -> Unit,
) {
    val name = scopeLanguageName(section.language)
    @Composable
    fun openLabel(resource: Int): String =
        if (section.labelled) stringResource(R.string.lab_consistency_progress_open_in, stringResource(resource), name) else stringResource(resource)

    ListCard(*section.views.map { view ->
        @Composable {
            when (view) {
                ProgressView.Words -> ListRow(
                    stringResource(R.string.lab_words_title),
                    wordsSummary(words),
                    onClick = { onOpen(view) },
                    onClickLabel = openLabel(R.string.lab_words_open),
                )
                ProgressView.Conjugations -> ListRow(
                    stringResource(R.string.lab_conjugations_title),
                    conjugations?.let { (used, total) -> pluralStringResource(R.plurals.lab_conjugations_cells_used, total, used, total) }
                        ?: stringResource(R.string.lab_conjugations_home_summary),
                    onClick = { onOpen(view) },
                    onClickLabel = openLabel(R.string.lab_conjugations_open),
                )
                ProgressView.Patterns -> ListRow(
                    stringResource(R.string.lab_patterns_title),
                    patternSummary ?: stringResource(R.string.lab_patterns_history_summary),
                    onClick = { onOpen(view) },
                    onClickLabel = openLabel(R.string.lab_patterns_open),
                ) {
                    patternSparkline?.let { Sparkline(it, Modifier.width(56.dp).height(24.dp).clearAndSetSemantics { }) }
                }
                else -> Unit
            }
        }
    }.toTypedArray())
}

/** "312 words you've written, 18 new this month", or what Words is for until there are any. */
@Composable
private fun wordsSummary(words: LanguageWords?): String {
    val count = words?.distinctWords ?: 0
    if (count == 0) return stringResource(R.string.lab_words_history_summary)
    val total = pluralStringResource(R.plurals.lab_ia_progress_words, count, count)
    val fresh = words?.newThisMonth ?: 0
    return if (fresh > 0) pluralStringResource(R.plurals.lab_ia_progress_words_new, fresh, total, fresh) else total
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

/** Every saved phrase; null until the file has been read. */
@Composable
private fun rememberPhrases(): List<Phrase>? {
    val context = LocalContext.current
    val store = remember(context) { PhrasebookStore.of(context) }
    val phrases by store.phrases.collectAsState()
    LaunchedEffect(store) { withContext(Dispatchers.IO) { store.load() } }
    return phrases
}
