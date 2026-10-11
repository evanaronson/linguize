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
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.components.SectionTitle

/** How to turn on the accessibility button, for apps whose selection menu doesn't show Linguize. */
@Composable
internal fun OtherAppsSection() {
    val context = LocalContext.current
    SectionTitle("When Linguize isn't in the menu")
    Text(
        "In some apps, like Telegram, Linguize isn't in the selection menu. There, turn on Linguize's " +
            "accessibility button and tap it while you type. It reads only the field you're typing in, " +
            "and only when you tap.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "1. Open Accessibility, choose Linguize, and turn on Linguize and its shortcut.\n" +
            "2. If Android says it's a restricted setting, open App info, tap ⋮, choose Allow restricted settings, " +
            "then try step 1 again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            context.openFromSettings(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("Open Accessibility") }
        OutlinedButton(onClick = {
            context.openFromSettings(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text("Open App info") }
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
        Toast.makeText(this, "Couldn't open that. No app can handle it.", Toast.LENGTH_SHORT).show()
    }
}
