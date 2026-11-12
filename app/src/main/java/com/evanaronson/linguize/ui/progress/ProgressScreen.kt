package com.evanaronson.linguize.ui.progress

import com.evanaronson.linguize.ui.components.EvidenceFailureNotice

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
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.words.LanguageWords
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
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
import com.evanaronson.linguize.ui.time.TimeHeadline
import com.evanaronson.linguize.ui.words.WordsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** A page Progress opens. Each shows the app's current language, or the block's language under Linguize. */
enum class ProgressView { Words, Conjugations, Patterns, Phrasebook, Time }

/**
 * Lab, Consistency: the languages Progress shows a block for. With a language chosen, just
 * that one (and no heading: the selector says it). Under Linguize (Auto, null), one block per
 * language in use ([LanguageScope.inUse]) of the languages [found] in the writer's data.
 */
internal fun progressLanguages(current: String?, found: Collection<String?>, mine: List<String> = LanguageScope.DEFAULT_LANGUAGES): List<String> =
    if (current != null) listOf(current) else LanguageScope.inUse(found, mine)

/**
 * Lab, Progress IA: the tab's sections, top to bottom. One headline for the whole tab (this
 * week's time), then the views of
 * the writer's own writing (a card per language, headed by its brand name under Linguize),
 * then what the writer kept (the Phrasebook, which is a collection, not a view).
 */
internal sealed interface ProgressSection {
    /** This week's time and the last weeks: how the writer is doing, at a glance. */
    data object Headline : ProgressSection

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
internal fun progressLayout(
    current: String?,
    found: Collection<String?>,
    mine: List<String> = LanguageScope.DEFAULT_LANGUAGES,
): List<ProgressSection> {
    val blocks = progressLanguages(current, found, mine)
    val labelled = current == null
    return buildList {
        add(ProgressSection.Headline)
        blocks.forEach { add(ProgressSection.Views(it, labelled, progressViews(it))) }
        add(ProgressSection.Saved)
    }
}

/**
 * Progress, one of the four destinations: how the writer is doing, all read from History.
 * Nothing here asks a model. See [progressLayout] for the order.
 *
 * With a language chosen at the top ([language]), every section, and every page it opens,
 * shows only that language. Under Linguize (Auto, null) the views have a card per language,
 * each headed by its brand name and icon, and [onOpen]
 * gets that language so its page opens in it. Time and the Phrasebook cover every language
 * once; their pages break them down.
 */
@Composable
fun ProgressScreen(
    patterns: PatternsViewModel,
    words: WordsViewModel,
    conjugations: ConjugationsViewModel,
    language: String?,
    /** The selector's languages, for each block's flag (Castilianize's follows the variety). */
    languageOptions: StateFlow<List<LanguageOption>>,
    /** Opens a page; the language is a block's or row's under Linguize, else null (the app's language applies). */
    onOpen: (ProgressView, String?) -> Unit,
) {
    val patternsState by patterns.state.collectAsState()
    val wordsState by words.state.collectAsState()
    val conjugationsState by conjugations.state.collectAsState()
    val options by languageOptions.collectAsState()
    LaunchedEffect(conjugations) { conjugations.refresh() }
    LaunchedEffect(patterns, language) { patterns.setLanguage(language) }

    val wordsByLanguage = wordsState.index?.languages.orEmpty().associateBy { it.language }
    val phrases = rememberPhrases()
    val found = wordsByLanguage.filterValues { it.totalWords > 0 }.keys +
        phrases.orEmpty().map { it.language } +
        conjugationsState.reports.filterValues { !it.isEmpty }.keys
    val pattern = if (language == null || patternsState.language != language) null
        else patternsState.snapshot?.report?.takeIf { it.worthShowing }

    ScreenColumn {
        if (wordsState.evidenceFailed || conjugationsState.failed) {
            EvidenceFailureNotice(wordsState.index != null || conjugationsState.reports.isNotEmpty()) {
                words.refresh()
                conjugations.refresh()
            }
        }
        progressLayout(language, found, options.map { it.code }).forEach { section ->
            when (section) {
                ProgressSection.Headline -> TimeHeadline(language) { onOpen(ProgressView.Time, null) }
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
    LaunchedEffect(store) {
        try {
            withContext(Dispatchers.IO) { store.load() }
        } catch (_: java.io.IOException) {
            // Keep the count unknown; the Phrasebook page presents the load error and retry.
        }
    }
    return phrases
}
