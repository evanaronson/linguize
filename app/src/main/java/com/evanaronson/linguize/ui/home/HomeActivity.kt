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
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.HistoryScreen
import com.evanaronson.linguize.ui.history.SessionAssumptionsScreen
import com.evanaronson.linguize.ui.history.SessionScreen
import com.evanaronson.linguize.ui.history.SessionViewModel
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
                                )
                                Screen.History -> HistoryScreen(
                                    history, settings,
                                    historyOn = settings.state?.historyEnabled,
                                    onOpen = ::open,
                                    onOpenSettings = openSettings,
                                )
                                Screen.Settings -> SettingsScreen(settings, onOpen = {
                                    navigation = navigation.open(Screen.SettingsChoice(it))
                                })
                                is Screen.SettingsChoice -> SettingsChoiceScreen(settings, current.page)
                                Screen.AccessibilitySetup -> AccessibilitySetupScreen()
                                is Screen.Detail -> SessionScreen(session, onDeleted = ::back, onOpenAssumptions = {
                                    navigation = navigation.open(Screen.DetailAssumptions(current.id))
                                })
                                is Screen.DetailAssumptions -> SessionAssumptionsScreen(session)
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
}
