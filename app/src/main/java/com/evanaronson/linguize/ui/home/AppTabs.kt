package com.evanaronson.linguize.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * The bottom bar: the four places someone comes to the app for. Talk (Chat), write or read
 * something specific (Write), see how they're doing (Progress), and look back (History).
 * Settings stays the gear in each top bar.
 */
@Composable
internal fun AppTabs(selected: Screen, onSelect: (Screen) -> Unit) {
    val label = stringResource(R.string.lab_home_ia_destinations)
    NavigationBar(
        modifier = Modifier.semantics { contentDescription = label },
        containerColor = Linguize.colors.ground,
    ) {
        Screen.tabs.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.tabIcon), contentDescription = null) },
                label = { Text(tab.tabLabel(), style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@get:DrawableRes
private val Screen.tabIcon: Int
    get() = when (this) {
        Screen.Chat -> R.drawable.lab_home_ia_ic_chat
        Screen.Progress -> R.drawable.lab_home_ia_ic_progress
        Screen.History -> R.drawable.lab_home_ia_ic_history
        else -> R.drawable.lab_home_ia_ic_write
    }

@Composable
private fun Screen.tabLabel(): String = when (this) {
    Screen.Chat -> stringResource(R.string.lab_chat_title)
    Screen.Progress -> stringResource(R.string.lab_home_ia_progress)
    Screen.History -> stringResource(R.string.history)
    else -> stringResource(R.string.lab_home_ia_write)
}
