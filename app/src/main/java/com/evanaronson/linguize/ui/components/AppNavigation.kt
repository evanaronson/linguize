package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * What the app's top bar shows. There are exactly two kinds, so every page looks the same:
 * a root tab ([Root]: Chat, Notes, Progress, History) has the wordmark on the left and, on
 * the right, the one language selector then the Settings gear; a page opened from a tab
 * ([Page]) has Back and its own title (a conversation: its title, and who the partner plays
 * under it). The bottom bar already names the tab, so a tab never repeats its name here.
 */
sealed interface TopBar {
    data object Root : TopBar
    data class Page(val title: String, val subtitle: String? = null) : TopBar
}

/**
 * The full app's navigation bar for [bar]. The activity owns the destination and back
 * action. [languageSelector] and [onOpenSettings] are drawn only on a [TopBar.Root]; Back
 * ([onBack], said as "Back to [backLabel]") only on a [TopBar.Page]. Cards keep their own
 * local drill-down navigation, including Meaning inside a popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppNavigation(
    bar: TopBar,
    onBack: () -> Unit,
    backLabel: String,
    onOpenSettings: () -> Unit,
    languageSelector: @Composable () -> Unit,
) {
    TopAppBar(
        title = {
            when (bar) {
                TopBar.Root -> Image(
                    painterResource(R.drawable.wordmark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(29.dp).semantics { heading() },
                )
                is TopBar.Page -> Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Text(bar.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    bar.subtitle?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        navigationIcon = {
            if (bar is TopBar.Page) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back_to, backLabel))
                }
            }
        },
        actions = {
            if (bar == TopBar.Root) {
                languageSelector()
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Linguize.colors.ground,
            scrolledContainerColor = Linguize.colors.ground,
        ),
    )
}
