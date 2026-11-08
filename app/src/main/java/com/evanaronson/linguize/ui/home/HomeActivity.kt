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
import com.evanaronson.linguize.ui.practice.PracticeEntry
import com.evanaronson.linguize.ui.practice.PracticeScreen
import com.evanaronson.linguize.ui.practice.PracticeViewModel
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
    private var navigation by mutableStateOf(Navigation())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigation = Navigation.restore(
            savedInstanceState?.getStringArrayList(STACK)
                ?: savedInstanceState?.getString(SCREEN)?.let { listOf(it) }
                ?: if (intent.getBooleanExtra(OPEN_SETTINGS, false)) Navigation().open(Screen.Settings).saved else null,
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
                        AppNavigation(
                            title = navigation.current.title(),
                            onBack = if (current == Screen.Home) null else ::back,
                            backLabel = navigation.parent?.title() ?: stringResource(R.string.app_name),
                            onOpenSettings = if (current == Screen.Home) openSettings else null,
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        saved.SaveableStateProvider(current.saved) {
                            when (current) {
                                Screen.Home -> HomeScreen(
                                    check, history,
                                    historyOn = settings.state?.historyEnabled,
                                    needsKey = settings.state?.hasKey == false,
                                    onOpenSettings = openSettings,
                                    onOpen = ::open,
                                    onViewHistory = { navigation = navigation.open(Screen.History) },
                                    onSetUpAccessibility = { navigation = navigation.open(Screen.AccessibilitySetup) },
                                    onOpenPhrasebook = { navigation = navigation.open(Screen.Phrasebook) },
                                    onFillGaps = { text ->
                                        if (text != null) { gaps.close(); gaps.draft = text }
                                        navigation = navigation.open(Screen.FillGaps)
                                    },
                                    onHelpReply = { navigation = navigation.open(Screen.LabReply) },
                                    onPhotoPicked = { photo.understand(this@HomeActivity, it); navigation = navigation.open(Screen.Photo) },
                                    patterns = patterns,
                                    onOpenPatterns = { navigation = navigation.open(Screen.Patterns) },
                                    practice = { PracticeEntry(practice) { navigation = navigation.open(Screen.Practice) } },
                                )
                                Screen.History -> HistoryScreen(
                                    history, settings,
                                    historyOn = settings.state?.historyEnabled,
                                    onOpen = ::open,
                                    onOpenSettings = openSettings,
                                    onOpenPatterns = { navigation = navigation.open(Screen.Patterns) },
                                )
                                Screen.Settings -> SettingsScreen(settings, onOpen = {
                                    navigation = navigation.open(Screen.SettingsChoice(it))
                                }, appVoiceRow = {
                                    AppVoiceSettingsRow(appVoice) { navigation = navigation.open(Screen.AppVoice()) }
                                }, memoryRow = {
                                    com.evanaronson.linguize.ui.memory.MemorySettingsRow { navigation = navigation.open(Screen.Memory) }
                                })
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
        navigation = if (intent.getBooleanExtra(OPEN_SETTINGS, false)) Navigation().open(Screen.Settings) else Navigation()
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
        fun homeIntent(context: Context) = Intent(context, HomeActivity::class.java)
        fun settingsIntent(context: Context) = homeIntent(context).putExtra(OPEN_SETTINGS, true)
    }
}

/** The title in the navigation bar, and in "Back to …"; null for Home, which shows the wordmark. */
@Composable
private fun Screen.title(): String? = when (this) {
    Screen.Home -> null
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
}
