package com.evanaronson.linguize.ui.popup

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.FloatingCard
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.BackButton
import com.evanaronson.linguize.ui.components.LocalInlineControls
import com.evanaronson.linguize.ui.settings.LocalSettingsHost
import com.evanaronson.linguize.ui.settings.SettingsHost
import com.evanaronson.linguize.ui.settings.SettingsContent
import com.evanaronson.linguize.ui.settings.SettingsViewModel

/** Settings is a page of the same popup, like Meaning. The check itself stays alive. */
@Composable
fun PopupCard(
    state: CardState,
    actions: CardActions,
    settings: SettingsViewModel,
    settingsOpen: Boolean,
    onBackFromSettings: () -> Unit,
    onDismiss: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenExternal: (Intent) -> Unit,
    onInputModeChanged: (Boolean) -> Unit = {},
    alignment: Alignment = Alignment.BottomCenter,
    topPadding: Dp = 0.dp,
    header: @Composable () -> Unit = {},
) {
    LaunchedEffect(settingsOpen) { onInputModeChanged(settingsOpen) }
    val saved = rememberSaveableStateHolder()
    CompositionLocalProvider(
        LocalInlineControls provides true,
        LocalSettingsHost provides SettingsHost(embedded = true, openExternal = onOpenExternal),
    ) {
        FloatingCard(
            state, actions,
            onDismiss = if (settingsOpen) onBackFromSettings else onDismiss,
            onOpenHome = onOpenHome,
            alignment = alignment,
            topPadding = topPadding,
            content = { modifier ->
                AnimatedContent(
                    targetState = settingsOpen,
                    modifier = modifier.then(if (settingsOpen) Modifier.imePadding() else Modifier),
                    transitionSpec = {
                        val direction = if (targetState) 1 else -1
                        (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -direction * it / 3 } + fadeOut())
                    },
                    label = "popup settings",
                ) { showingSettings ->
                    saved.SaveableStateProvider(if (showingSettings) "settings" else "check") {
                        if (showingSettings) {
                            Surface(
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 6.dp,
                                shadowElevation = 8.dp,
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        BackButton(onBackFromSettings)
                                        Text("Settings", style = MaterialTheme.typography.titleMedium)
                                    }
                                    SettingsContent(settings, Modifier.weight(1f, fill = false))
                                }
                            }
                        } else {
                            ResultCard(state, actions, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            header = { if (!settingsOpen) header() },
        )
    }
}
