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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.HistoryScreen
import com.evanaronson.linguize.ui.history.SessionScreen
import com.evanaronson.linguize.ui.history.SessionViewModel
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
        (navigation.current as? Screen.Detail)?.let { session.load(it.id) }
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
                            title = when (current) {
                                Screen.Home -> null
                                Screen.History -> stringResource(R.string.history)
                                Screen.Settings -> stringResource(R.string.settings)
                                is Screen.Detail -> stringResource(R.string.saved_check)
                            },
                            onBack = if (current == Screen.Home) null else ::back,
                            backLabel = when (navigation.parent) {
                                Screen.History -> stringResource(R.string.history)
                                Screen.Settings -> stringResource(R.string.settings)
                                else -> stringResource(R.string.app_name)
                            },
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
                                    onOpenSettings = openSettings,
                                    onOpen = ::open,
                                    onViewHistory = { navigation = navigation.open(Screen.History) },
                                )
                                Screen.History -> HistoryScreen(
                                    history, settings,
                                    historyOn = settings.state?.historyEnabled,
                                    onOpen = ::open,
                                    onOpenSettings = openSettings,
                                )
                                Screen.Settings -> SettingsScreen(settings)
                                is Screen.Detail -> SessionScreen(session, onDeleted = ::back)
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

    companion object {
        private const val SCREEN = "screen"
        private const val STACK = "navigation"
        private const val OPEN_SETTINGS = "openSettings"
        fun homeIntent(context: Context) = Intent(context, HomeActivity::class.java)
        fun settingsIntent(context: Context) = homeIntent(context).putExtra(OPEN_SETTINGS, true)
    }
}
