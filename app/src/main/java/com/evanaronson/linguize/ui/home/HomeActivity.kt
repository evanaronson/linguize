package com.evanaronson.linguize.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.appvoice.AppVoiceListScreen
import com.evanaronson.linguize.ui.appvoice.AppVoiceRuleScreen
import com.evanaronson.linguize.ui.appvoice.AppVoiceSettingsRow
import com.evanaronson.linguize.ui.appvoice.AppVoiceSettingsViewModel
import com.evanaronson.linguize.ui.appvoice.appVoiceTitle
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.components.TopBar
import com.evanaronson.linguize.ui.components.imeVisible
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.HistoryScreen
import com.evanaronson.linguize.ui.history.SessionScreen
import com.evanaronson.linguize.ui.history.SessionViewModel
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.phrasebook.PhrasebookScreen
import com.evanaronson.linguize.ui.phrasebook.PhrasebookViewModel
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.ui.patterns.PatternDetailScreen
import com.evanaronson.linguize.ui.patterns.PatternsScreen
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.labelResource
import com.evanaronson.linguize.ui.practice.PracticeScreen
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.settings.SettingsChoiceScreen
import com.evanaronson.linguize.ui.settings.SettingsScreen
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.theme.AppTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

/** The full app has normal navigation; a popup's page and edits belong to its own host. */
class HomeActivity : ComponentActivity() {
    private val settings: SettingsViewModel by viewModels { SettingsViewModel.factory(App.of(this)) }
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val history: HistoryViewModel by viewModels { HistoryViewModel.factory(App.of(this)) }
    private val session: SessionViewModel by viewModels { SessionViewModel.factory(App.of(this)) }
    private val phrasebook: PhrasebookViewModel by viewModels { PhrasebookViewModel.factory(PhrasebookStore.of(this)) }
    private val appVoice: AppVoiceSettingsViewModel by viewModels { AppVoiceSettingsViewModel.factory(App.of(this)) }
    private val patterns: PatternsViewModel by viewModels { PatternsViewModel.factory(App.of(this)) }
    private val practice: PracticeViewModel by viewModels { PracticeViewModel.factory(App.of(this)) }
    private val memory: com.evanaronson.linguize.ui.memory.MemoryViewModel by viewModels {
        com.evanaronson.linguize.ui.memory.MemoryViewModel.factory(App.of(this).memoryStore)
    }
    private val words: com.evanaronson.linguize.ui.words.WordsViewModel by viewModels { com.evanaronson.linguize.ui.words.WordsViewModel.factory(App.of(this)) }
    private val conjugations: com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel by viewModels {
        com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel.factory(App.of(this))
    }
    private var navigation by mutableStateOf(Navigation())
    private val appLanguage by lazy { com.evanaronson.linguize.ui.language.AppLanguage.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigation = Navigation.restore(
            savedInstanceState?.getStringArrayList(STACK)
                ?: savedInstanceState?.getString(SCREEN)?.let { listOf(it) }
                ?: startNavigation(this, intent.getBooleanExtra(OPEN_SETTINGS, false), requestedTab(intent)).saved,
        )
        navigation.current.sessionId?.let(session::load)
        com.evanaronson.linguize.ui.quicktile.QuickLaunch.publishInBackground(this)
        setContent {
            AppTheme {
                val entry = navigation
                val route = entry.current
                val current = route.destination
                val openSettings = { navigation = navigation.open(Screen.Settings) }
                // Lab, Language scope: one current language for Chat, Notes, Progress and History; null is Linguize (Auto).
                val language by appLanguage.current.collectAsState()
                LaunchedEffect(Unit) { appLanguage.load() }
                val scopedLanguage = route.effectiveLanguage(language)
                LaunchedEffect(appLanguage) {
                    // Initial collection must preserve a restored Auto block. Later changes,
                    // including Chat.follow, make live pages follow the new app choice.
                    appLanguage.current.drop(1).collect { navigation = navigation.afterLanguageChange() }
                }
                val languageSelector: @Composable () -> Unit = {
                    com.evanaronson.linguize.ui.language.LanguageSwitch(appLanguage, onAddLanguages = {
                        navigation = navigation.open(Screen.Languages)
                    }) { code ->
                        if (code != appLanguage.current.value) {
                            appLanguage.select(code)
                            navigation = navigation.afterLanguageSwitch()
                        }
                    }
                }
                // Back closes the keyboard first; only then does it go back a page.
                com.evanaronson.linguize.ui.components.KeyboardBackHandler()
                com.evanaronson.linguize.ui.components.NavigationBackHandler(enabled = navigation.parent != null, onBack = ::back)
                val saved = rememberNavigationStateHolder(navigation)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // Lab, Consistency: one bar for every page. A conversation draws the same bar with its
                        // own title, which only it knows.
                        if (current !is Screen.ChatThread) AppNavigation(
                            bar = current.topBar(current.title()),
                            onBack = ::back,
                            backLabel = navigation.parent?.title() ?: stringResource(R.string.app_name),
                            onOpenSettings = openSettings,
                            languageSelector = languageSelector,
                        )
                    },
                    bottomBar = {
                        // Typing (the Notes editor, the chat composer) gets the room while the keyboard is up.
                        // The app opens into a conversation, so the other tabs stay one tap away there too.
                        if ((current.isTab || current is Screen.ChatThread || current is Screen.Note) && !imeVisible()) AppTabs(navigation.tab) { navigation = navigation.open(it) }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        saved.SaveableStateProvider(route.saveableKey) {
                            when (current) {
                                Screen.Notes -> NotesScreen { navigation = navigation.open(Screen.Note(it)) }
                                Screen.Progress -> com.evanaronson.linguize.ui.progress.ProgressScreen(
                                    practice, patterns, words, conjugations, language, appLanguage.options,
                                ) { view, block ->
                                    navigation = navigation.open(progressDestination(view, language, block))
                                }
                                Screen.History -> HistoryScreen(
                                    history, settings,
                                    historyOn = settings.state?.historyEnabled,
                                    language = language,
                                    onOpen = ::open,
                                    onOpenSettings = openSettings,
                                )
                                Screen.Settings -> SettingsScreen(settings, onOpen = {
                                    navigation = navigation.open(Screen.SettingsChoice(it))
                                }, appVoiceRow = {
                                    AppVoiceSettingsRow(appVoice) { navigation = navigation.open(Screen.AppVoice()) }
                                }, memoryRow = {
                                    com.evanaronson.linguize.ui.memory.MemorySettingsRow { navigation = navigation.open(Screen.Memory) }
                                }, onSetUpAccessibility = { navigation = navigation.open(Screen.AccessibilitySetup) }, languagesRow = {
                                    com.evanaronson.linguize.ui.language.LanguagesSettingsRow(appLanguage) { navigation = navigation.open(Screen.Languages) }
                                }, aboutSection = {
                                    com.evanaronson.linguize.ui.settings.AboutSection { navigation = navigation.open(Screen.Notices) }
                                })
                                is Screen.SettingsChoice -> SettingsChoiceScreen(settings, current.page)
                                Screen.AccessibilitySetup -> AccessibilitySetupScreen()
                                is Screen.Detail -> SessionScreen(session, onDeleted = ::back, onOpenConversation = { navigation = navigation.open(Screen.Chat).open(Screen.ChatThread(it)) })
                                Screen.Phrasebook -> PhrasebookScreen(phrasebook, scopedLanguage, onOpenCheck = ::open)
                                is Screen.AppVoice -> current.app?.let { AppVoiceRuleScreen(appVoice, it) }
                                    ?: AppVoiceListScreen(appVoice) { navigation = navigation.open(Screen.AppVoice(it)) }
                                Screen.Patterns -> PatternsScreen(patterns, settings.state?.historyEnabled, scopedLanguage, onOpenPattern = {
                                    navigation = navigation.open(Screen.PatternDetail(it.token).inScope(route.scope))
                                }, onOpenSettings = openSettings)
                                is Screen.PatternDetail -> PatternDetailScreen(
                                    patterns, PatternKind.read(current.kind) ?: PatternKind.Other, scopedLanguage, onOpenCheck = ::open,
                                )
                                Screen.Practice -> PracticeScreen(practice, scopedLanguage, onDone = ::back)
                                Screen.Memory -> com.evanaronson.linguize.ui.memory.MemoryScreen(memory)
                                Screen.Words -> com.evanaronson.linguize.ui.words.WordsScreen(words, settings.state?.historyEnabled, scopedLanguage) { language, key, level ->
                                    navigation = navigation.open(Screen.WordDetail(language, key, level.token))
                                }
                                is Screen.WordDetail -> com.evanaronson.linguize.ui.words.WordDetailScreen(
                                    words, current.language, current.key, current.level, onOpenCheck = ::open,
                                )
                                Screen.Chat -> com.evanaronson.linguize.ui.chat.ChatListScreen(language, openSettings) {
                                    navigation = navigation.open(Screen.ChatThread(it))
                                }
                                is Screen.ChatThread -> com.evanaronson.linguize.ui.chat.ChatThreadScreen(
                                    current.id, language, onBack = { if (navigation.owns(entry)) back() },
                                    onResolved = { id ->
                                        // Only while the app is still on "the current conversation".
                                        if (navigation.owns(entry) && current.id == null) {
                                            navigation = if (id != null) navigation.replace(Screen.ChatThread(id)) else navigation.back()
                                        }
                                    },
                                    onOpenSettings = openSettings,
                                )
                                Screen.Conjugations -> com.evanaronson.linguize.ui.conjugations.ConjugationsScreen(
                                    conjugations, scopedLanguage,
                                    onOpenCell = { language, cell -> navigation = navigation.open(Screen.ConjugationCell(language, cell.token)) },
                                    onOpenVerbs = { language, cell -> navigation = navigation.open(conjugationVerbsScreen(language, cell)) },
                                    onOpenVerb = { language, lemma -> navigation = navigation.open(conjugationVerbWordScreen(language, lemma)) },
                                )
                                is Screen.ConjugationCell -> com.evanaronson.linguize.core.conjugations.Cell.read(current.cell)?.let {
                                    com.evanaronson.linguize.ui.conjugations.ConjugationCellScreen(
                                        conjugations, current.language, it,
                                        onOpenVerbs = { language, cell -> navigation = navigation.open(conjugationVerbsScreen(language, cell)) },
                                        onOpenVerb = { language, lemma -> navigation = navigation.open(conjugationVerbWordScreen(language, lemma)) },
                                    )
                                }
                                Screen.TimeSpent -> com.evanaronson.linguize.ui.time.TimeSpentScreen(settings.state?.historyEnabled, scopedLanguage)
                                Screen.Languages -> com.evanaronson.linguize.ui.language.LanguagesScreen(appLanguage)
                                Screen.Notices -> com.evanaronson.linguize.ui.settings.OpenSourceNoticesScreen()
                                is Screen.ConjugationVerbs -> com.evanaronson.linguize.ui.conjugations.ConjugationVerbsScreen(
                                    conjugations, current.language, current.cell?.let(com.evanaronson.linguize.core.conjugations.Cell::read),
                                ) { language, lemma -> navigation = navigation.open(conjugationVerbWordScreen(language, lemma)) }
                                is Screen.Note -> NotePage(
                                    current.id, check,
                                    needsKey = settings.state?.hasKey == false,
                                    onOpenSettings = openSettings,
                                    onResolved = { id ->
                                        // Only while the app is still on "the current note".
                                        if (id != null && navigation.owns(entry) && current.id == null) navigation = navigation.resolveNote(id)
                                    },
                                    language = language,
                                )
                                is Screen.Scoped -> error("Scoped route was not unwrapped")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settings.refresh()
        appVoice.refresh()
        navigation.current.sessionId?.let(session::load)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigation = startNavigation(this, intent.getBooleanExtra(OPEN_SETTINGS, false), requestedTab(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STACK, ArrayList(navigation.saved))
    }

    private fun open(id: String) {
        session.load(id)
        navigation = navigation.open(Screen.Detail(id))
    }

    private fun back() {
        if (navigation.current == Screen.Settings) {
            check.recheckIfStale()
        }
        navigation = navigation.back()
    }

    /** The saved check a screen shows, which the session view model loads. */
    private val Screen.sessionId: String?
        get() = when (this) {
            is Screen.Detail -> id
            else -> null
        }

    companion object {
        private const val SCREEN = "screen"
        private const val STACK = "navigation"
        private const val OPEN_SETTINGS = "openSettings"
        private const val OPEN_TAB = "openTab"
        fun homeIntent(context: Context) = Intent(context, HomeActivity::class.java)
        fun settingsIntent(context: Context) = homeIntent(context).putExtra(OPEN_SETTINGS, true)

        /** Opens Notes and its current note (the Notes launcher shortcut), above whichever tab the app starts on. */
        fun notesIntent(context: Context) = homeIntent(context).putExtra(OPEN_TAB, Screen.Notes.saved)

        /** The bottom-bar destination an intent asks for, if any. */
        internal fun requestedTab(intent: Intent?): Screen? = Screen.restore(intent?.getStringExtra(OPEN_TAB))?.takeIf { it.isTab }
    }
}

/**
 * Lab, Consistency: the bar a screen gets. Every root tab has the same one (the wordmark, the
 * language selector, Settings); every other page has Back and its [title].
 */
internal fun Screen.topBar(title: String, subtitle: String? = null): TopBar =
    if (isTab) TopBar.Root else TopBar.Page(title, subtitle, languageSelector = this is Screen.Note)

/** A page's title in the navigation bar, and in "Back to …"; a tab's name (its bar shows the wordmark). */
@Composable
private fun Screen.title(): String = when (this) {
    is Screen.Scoped -> page.title()
    Screen.Notes -> stringResource(R.string.lab_consistency_notes)
    Screen.Progress -> stringResource(R.string.lab_home_ia_progress)
    Screen.History -> stringResource(R.string.history)
    Screen.Settings -> stringResource(R.string.settings)
    Screen.AccessibilitySetup -> stringResource(R.string.accessibility_button)
    is Screen.SettingsChoice -> stringResource(page.title)
    is Screen.Detail -> stringResource(R.string.saved_check)
    Screen.Phrasebook -> stringResource(R.string.lab_phrasebook_title)
    is Screen.AppVoice -> appVoiceTitle(app)
    Screen.Patterns -> stringResource(R.string.lab_patterns_title)
    is Screen.PatternDetail -> stringResource((PatternKind.read(kind) ?: PatternKind.Other).labelResource)
    Screen.Practice -> stringResource(R.string.lab_practice_title)
    Screen.Memory -> stringResource(R.string.lab_memory_title)
    Screen.Words -> stringResource(R.string.lab_words_title)
    is Screen.WordDetail -> com.evanaronson.linguize.ui.words.wordTitle(key)
    Screen.Chat -> stringResource(R.string.lab_chat_title)
    Screen.Conjugations -> stringResource(R.string.lab_conjugations_title)
    is Screen.ConjugationCell -> com.evanaronson.linguize.core.conjugations.Cell.read(cell)
        ?.let { com.evanaronson.linguize.ui.conjugations.conjugationCellTitle(language, it) }
        ?: stringResource(R.string.lab_conjugations_title)
    Screen.TimeSpent -> stringResource(R.string.lab_time_spent_title)
    is Screen.ChatThread -> stringResource(R.string.lab_chat_title)
    Screen.Languages -> stringResource(R.string.lab_lang_selector_title)
    Screen.Notices -> stringResource(R.string.lab_lang_selector_notices)
    is Screen.ConjugationVerbs -> com.evanaronson.linguize.ui.conjugations.conjugationVerbsTitle(
        language, cell?.let(com.evanaronson.linguize.core.conjugations.Cell::read),
    )
    is Screen.Note -> stringResource(R.string.lab_notes_nav_title)
}
