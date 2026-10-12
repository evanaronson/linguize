package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.history.HistoryViewModel
import com.evanaronson.linguize.ui.history.RecentSection

/**
 * The launcher content below the app navigation: check something now, set up the
 * accessibility shortcut, and browse previous checks. [historyOn] is null until the setting has been read.
 */
@Composable
fun HomeScreen(
    check: CheckViewModel,
    history: HistoryViewModel,
    historyOn: Boolean?,
    onOpenSettings: () -> Unit,
    onOpen: (id: String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TryIt(check, onOpenSettings)
            AccessibilitySetup()
            RecentSection(history, historyOn, snackbar, onOpen, onOpenSettings)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
