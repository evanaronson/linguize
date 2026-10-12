package com.evanaronson.linguize.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.components.SectionTitle

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
internal fun AccessibilitySetup() {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.selection_menu_unavailable))
    Text(
        stringResource(R.string.accessibility_setup_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.accessibility_setup_steps),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.openExternal(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text(stringResource(R.string.open_accessibility)) }
        OutlinedButton(onClick = {
            context.openExternal(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text(stringResource(R.string.open_app_info)) }
    }
}
