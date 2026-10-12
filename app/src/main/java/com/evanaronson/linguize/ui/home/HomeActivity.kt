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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.history.HistoryViewModel
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
    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screen = Screen.restore(savedInstanceState?.getString(SCREEN)) ?: Screen.Home
        (screen as? Screen.Detail)?.let { session.load(it.id) }
        setContent {
            AppTheme {
                val current = screen
                val openSettings = { screen = Screen.Settings }
                BackHandler(enabled = current != Screen.Home, onBack = ::back)
                val saved = rememberSaveableStateHolder()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        AppNavigation(
                            title = when (current) {
                                Screen.Home -> null
                                Screen.Settings -> stringResource(R.string.settings)
                                is Screen.Detail -> stringResource(R.string.saved_check)
                            },
                            onBack = if (current == Screen.Home) null else ::back,
                            onOpenSettings = if (current == Screen.Home) openSettings else null,
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        when (current) {
                            Screen.Home -> saved.SaveableStateProvider(current.saved) {
                                HomeScreen(check, history,
                                    historyOn = settings.state?.historyEnabled,
                                    onOpenSettings = openSettings,
                                    onOpen = ::open)
                            }
                            Screen.Settings -> saved.SaveableStateProvider(current.saved) {
                                SettingsScreen(settings)
                            }
                            is Screen.Detail -> SessionScreen(session, onDeleted = ::back)
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
        screen = Screen.Home
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SCREEN, screen.saved)
    }

    private fun open(id: String) {
        session.load(id)
        screen = Screen.Detail(id)
    }

    private fun back() {
        if (screen == Screen.Settings) {
            check.recheckIfStale()
        }
        screen = Screen.Home
    }

    companion object {
        private const val SCREEN = "screen"
        fun homeIntent(context: Context) = Intent(context, HomeActivity::class.java)
    }
}
