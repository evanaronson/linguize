package com.evanaronson.linguize.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
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
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.HistoryScreen
import com.evanaronson.linguize.ui.history.SessionAssumptionsScreen
import com.evanaronson.linguize.ui.history.SessionScreen
import com.evanaronson.linguize.ui.history.SessionViewModel
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.phrasebook.PhrasebookScreen
import com.evanaronson.linguize.ui.phrasebook.PhrasebookViewModel
import com.evanaronson.linguize.ui.gaps.GapFillScreen
import com.evanaronson.linguize.ui.gaps.GapFillViewModel
import com.evanaronson.linguize.ui.photo.PhotoScreen
import com.evanaronson.linguize.ui.photo.PhotoViewModel
import com.evanaronson.linguize.ui.photo.understand
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.ui.patterns.PatternDetailScreen
import com.evanaronson.linguize.ui.patterns.PatternsScreen
import com.evanaronson.linguize.ui.patterns.PatternsViewModel
import com.evanaronson.linguize.ui.patterns.labelResource
import com.evanaronson.linguize.ui.practice.PracticeScreen
import com.evanaronson.linguize.ui.practice.PracticeViewModel
import com.evanaronson.linguize.ui.progress.ProgressView
import com.evanaronson.linguize.ui.settings.SettingsChoiceScreen
import com.evanaronson.linguize.ui.settings.SettingsScreen
import com.evanaronson.linguize.ui.settings.SettingsViewModel
import com.evanaronson.linguize.ui.theme.AppTheme

/** The full app has normal navigation; a popup's page and edits belong to its own host. */
class HomeActivity : ComponentActivity() {
    private val settings: SettingsViewModel by viewModels { SettingsViewModel.factory(App.of(this)) }
    private val check: CheckViewModel by viewModels { CheckViewModel.factory(App.of(this)) }
    private val history: HistoryViewModel by viewModels { HistoryViewModel.factory(App.of(this)) }
    private val session: SessionViewModel by viewModels { SessionViewModel.factory(App.of(this)) }
    private val phrasebook: PhrasebookViewModel by viewModels { PhrasebookViewModel.factory(PhrasebookStore.of(this)) }
    private val appVoice: AppVoiceSettingsViewModel by viewModels { AppVoiceSettingsViewModel.factory(App.of(this)) }
    private val gaps: GapFillViewModel by viewModels { GapFillViewModel.factory(App.of(this)) }
    private val photo: PhotoViewModel by viewModels { PhotoViewModel.factory(App.of(this)) }
    private val patterns: PatternsViewModel by viewModels { PatternsViewModel.factory(App.of(this)) }
    private val practice: PracticeViewModel by viewModels { PracticeViewModel.factory(App.of(this)) }
    private val memory: com.evanaronson.linguize.ui.memory.MemoryViewModel by viewModels {
        com.evanaronson.linguize.ui.memory.MemoryViewModel.factory(App.of(this).memoryStore)
    }
    private val words: com.evanaronson.linguize.ui.words.WordsViewModel by viewModels { com.evanaronson.linguize.ui.words.WordsViewModel.factory(App.of(this)) }
    private val vocabGaps: com.evanaronson.linguize.ui.vocabgaps.VocabGapsViewModel by viewModels {
        com.evanaronson.linguize.ui.vocabgaps.VocabGapsViewModel.factory(App.of(this))
    }
    private val conjugations: com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel by viewModels {
        com.evanaronson.linguize.ui.conjugations.ConjugationsViewModel.factory(App.of(this))
    }
    private var navigation by mutableStateOf(Navigation())

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
                val current = navigation.current
                val openSettings = { navigation = navigation.open(Screen.Settings) }
                BackHandler(enabled = navigation.parent != null, onBack = ::back)
                val saved = rememberNavigationStateHolder(navigation)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        // The chat draws its own bar (conversations, language, Settings).
                        if (current != Screen.Chat) AppNavigation(
                            title = current.title(),
                            // Tabs are siblings: Back from one returns to the start, but no arrow says so.
                            onBack = if (navigation.parent == null || current.isTab) null else ::back,
                            backLabel = navigation.parent?.let { it.title() ?: stringResource(R.string.lab_home_ia_write) }
                                ?: stringResource(R.string.app_name),
                            onOpenSettings = if (current.isTab) openSettings else null,
                        )
                    },
                    bottomBar = {
                        // Typing (Try it, the chat composer) gets the room while the keyboard is up.
                        if (current.isTab && !imeVisible()) AppTabs(navigation.tab) { navigation = navigation.open(it) }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        saved.SaveableStateProvider(current.saved) {
                            when (current) {
                                Screen.Write -> WriteScreen(
                                    check,
                                    needsKey = settings.state?.hasKey == false,
                                    onOpenSettings = openSettings,
                                    onFillGaps = { text ->
                                        if (text != null) { gaps.close(); gaps.draft = text }
                                        navigation = navigation.open(Screen.FillGaps)
                                    },
                                    onHelpReply = { navigation = navigation.open(Screen.LabReply) },
                                    onPhotoPicked = { photo.understand(this@HomeActivity, it); navigation = navigation.open(Screen.Photo) },
                                )
                                Screen.Progress -> com.evanaronson.linguize.ui.progress.ProgressScreen(
                                    practice, patterns, words, conjugations, vocabGaps,
                                ) { view -> navigation = navigation.open(view.screen) }
                                Screen.History -> HistoryScreen(
                                    history, settings,
                                    historyOn = settings.state?.historyEnabled,
                                    onOpen = ::open,
                                    onOpenSettings = openSettings,
                                    onOpenThread = { com.evanaronson.linguize.ui.chat.ChatRequests.conversation = it; navigation = navigation.open(Screen.Chat) },
                                )
                                Screen.Settings -> SettingsScreen(settings, onOpen = {
                                    navigation = navigation.open(Screen.SettingsChoice(it))
                                }, appVoiceRow = {
                                    AppVoiceSettingsRow(appVoice) { navigation = navigation.open(Screen.AppVoice()) }
                                }, memoryRow = {
                                    com.evanaronson.linguize.ui.memory.MemorySettingsRow { navigation = navigation.open(Screen.Memory) }
                                }, onSetUpAccessibility = { navigation = navigation.open(Screen.AccessibilitySetup) })
                                is Screen.SettingsChoice -> SettingsChoiceScreen(settings, current.page)
                                Screen.AccessibilitySetup -> AccessibilitySetupScreen()
                                is Screen.Detail -> SessionScreen(session, onDeleted = ::back, onOpenAssumptions = {
                                    navigation = navigation.open(Screen.DetailAssumptions(current.id))
                                })
                                is Screen.DetailAssumptions -> SessionAssumptionsScreen(session)
                                Screen.Phrasebook -> PhrasebookScreen(phrasebook, onOpenCheck = ::open)
                                is Screen.AppVoice -> current.app?.let { AppVoiceRuleScreen(appVoice, it) }
                                    ?: AppVoiceListScreen(appVoice) { navigation = navigation.open(Screen.AppVoice(it)) }
                                Screen.FillGaps -> GapFillScreen(gaps, onOpenSettings = openSettings)
                                Screen.LabReply -> com.evanaronson.linguize.ui.reply.ReplyScreen(onOpenSettings = openSettings)
                                Screen.Photo -> PhotoScreen(photo, onOpenSettings = openSettings)
                                Screen.Patterns -> PatternsScreen(patterns, settings.state?.historyEnabled, onOpenPattern = {
                                    navigation = navigation.open(Screen.PatternDetail(it.token))
                                }, onOpenSettings = openSettings)
                                is Screen.PatternDetail -> PatternDetailScreen(
                                    patterns, PatternKind.read(current.kind) ?: PatternKind.Other, onOpenCheck = ::open,
                                )
                                Screen.Practice -> PracticeScreen(practice, onDone = ::back)
                                Screen.Memory -> com.evanaronson.linguize.ui.memory.MemoryScreen(memory)
                                Screen.Words -> com.evanaronson.linguize.ui.words.WordsScreen(words, settings.state?.historyEnabled) { language, key, level ->
                                    navigation = navigation.open(Screen.WordDetail(language, key, level.token))
                                }
                                is Screen.WordDetail -> com.evanaronson.linguize.ui.words.WordDetailScreen(
                                    words, current.language, current.key, current.level, onOpenCheck = ::open,
                                )
                                Screen.Chat -> com.evanaronson.linguize.ui.chat.ChatScreen(onOpenSettings = openSettings)
                                Screen.VocabGaps -> com.evanaronson.linguize.ui.vocabgaps.VocabGapsScreen(vocabGaps)
                                Screen.Conjugations -> com.evanaronson.linguize.ui.conjugations.ConjugationsScreen(conjugations) { language, cell ->
                                    navigation = navigation.open(Screen.ConjugationCell(language, cell.token))
                                }
                                is Screen.ConjugationCell -> com.evanaronson.linguize.core.conjugations.Cell.read(current.cell)?.let {
                                    com.evanaronson.linguize.ui.conjugations.ConjugationCellScreen(conjugations, current.language, it)
                                }
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
            gaps.retryIfFailed()
            photo.recheckIfStale()
        }
        navigation = navigation.back()
    }

    /** The saved check a screen shows, which the session view model loads. */
    private val Screen.sessionId: String?
        get() = when (this) {
            is Screen.Detail -> id
            is Screen.DetailAssumptions -> id
            else -> null
        }

    companion object {
        private const val SCREEN = "screen"
        private const val STACK = "navigation"
        private const val OPEN_SETTINGS = "openSettings"
        private const val OPEN_TAB = "openTab"
        fun homeIntent(context: Context) = Intent(context, HomeActivity::class.java)
        fun settingsIntent(context: Context) = homeIntent(context).putExtra(OPEN_SETTINGS, true)

        /** Opens Write, where Try it is (the Try it launcher shortcut), above whichever tab the app starts on. */
        fun writeIntent(context: Context) = homeIntent(context).putExtra(OPEN_TAB, Screen.Write.saved)

        /** The bottom-bar destination an intent asks for, if any. */
        internal fun requestedTab(intent: Intent?): Screen? = Screen.restore(intent?.getStringExtra(OPEN_TAB))?.takeIf { it.isTab }
    }
}

/** The keyboard is showing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imeVisible(): Boolean = WindowInsets.isImeVisible

/** Where a [ProgressView] lives in the navigation. */
private val ProgressView.screen: Screen
    get() = when (this) {
        ProgressView.Practice -> Screen.Practice
        ProgressView.Words -> Screen.Words
        ProgressView.Conjugations -> Screen.Conjugations
        ProgressView.VocabGaps -> Screen.VocabGaps
        ProgressView.Patterns -> Screen.Patterns
        ProgressView.Phrasebook -> Screen.Phrasebook
    }

/** The title in the navigation bar, and in "Back to …"; null for Write, which shows the wordmark. */
@Composable
private fun Screen.title(): String? = when (this) {
    Screen.Write -> null
    Screen.Progress -> stringResource(R.string.lab_home_ia_progress)
    Screen.History -> stringResource(R.string.history)
    Screen.Settings -> stringResource(R.string.settings)
    Screen.AccessibilitySetup -> stringResource(R.string.accessibility_button)
    is Screen.SettingsChoice -> stringResource(page.title)
    is Screen.Detail -> stringResource(R.string.saved_check)
    is Screen.DetailAssumptions -> stringResource(R.string.assumptions)
    Screen.Phrasebook -> stringResource(R.string.lab_phrasebook_title)
    is Screen.AppVoice -> appVoiceTitle(app)
    Screen.FillGaps -> stringResource(R.string.lab_gap_filler_title)
    Screen.LabReply -> stringResource(R.string.lab_reply_drafter_help_me_reply)
    Screen.Photo -> stringResource(R.string.lab_share_photo_title)
    Screen.Patterns -> stringResource(R.string.lab_patterns_title)
    is Screen.PatternDetail -> stringResource((PatternKind.read(kind) ?: PatternKind.Other).labelResource)
    Screen.Practice -> stringResource(R.string.lab_practice_title)
    Screen.Memory -> stringResource(R.string.lab_memory_title)
    Screen.Words -> stringResource(R.string.lab_words_title)
    is Screen.WordDetail -> if (level == "x") key.substringBeforeLast('/') else key
    Screen.Chat -> stringResource(R.string.lab_chat_title)
    Screen.VocabGaps -> stringResource(R.string.lab_vocab_gaps_title)
    Screen.Conjugations -> stringResource(R.string.lab_conjugations_title)
    is Screen.ConjugationCell -> com.evanaronson.linguize.core.conjugations.Cell.read(cell)
        ?.let { com.evanaronson.linguize.ui.conjugations.conjugationCellTitle(language, it) }
        ?: stringResource(R.string.lab_conjugations_title)
}
