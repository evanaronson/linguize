package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.Image
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

/**
 * The full app's navigation: branding and settings at the root, Back and a title on
 * child pages. The activity owns the destination and back action. Cards keep their
 * own local drill-down navigation, including Meaning inside a popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppNavigation(
    title: String?,
    onBack: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    TopAppBar(
        title = {
            if (title == null) {
                Image(
                    painterResource(R.drawable.wordmark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(36.dp).semantics { heading() },
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
            }
        },
        navigationIcon = {
            onBack?.let { back ->
                IconButton(onClick = back) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            onOpenSettings?.let { openSettings ->
                IconButton(onClick = openSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
