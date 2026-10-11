package com.evanaronson.linguize.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.SectionTitle

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
internal fun OtherAppsSection() {
    val context = LocalContext.current
    val openExternal = LocalSettingsHost.current.openExternal ?: context::openFromSettings
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
    // Temporary phone-only probe for the silent ChatGPT shortcut, not a diagnostics screen.
    val app = App.of(context)
    Text(
        stringResource(R.string.shortcut_debug_status, app.shortcutTestTaps, app.shortcutTestStage,
            stringResource(when (app.shortcutTestAvailable) {
                true -> R.string.shortcut_available
                false -> R.string.shortcut_unavailable
                null -> R.string.shortcut_unknown
            })),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            openExternal(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text(stringResource(R.string.open_accessibility)) }
        OutlinedButton(onClick = {
            openExternal(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text(stringResource(R.string.open_app_info)) }
    }
}

/**
 * Opens another app's screen from settings. Marked as not the user leaving, so a
 * card waiting behind settings stays hidden (see `HomeActivity.onUserLeaveHint`).
 * A phone with nothing to open it (no browser, say) gets a toast instead of a crash.
 */
internal fun Context.openFromSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.open_external_failed, Toast.LENGTH_SHORT).show()
    }
}
