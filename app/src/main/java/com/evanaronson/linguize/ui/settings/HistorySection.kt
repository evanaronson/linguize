package com.evanaronson.linguize.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evanaronson.linguize.ui.components.SectionTitle
import com.evanaronson.linguize.ui.components.shortDate

/** Whether checks are remembered, how many are, and Clear and Export. Browsing them is on Home. */
@Composable
internal fun HistorySection(state: SettingsState, settings: SettingsViewModel) {
    val context = LocalContext.current
    val kept by settings.history.collectAsState()
    val count = kept?.count ?: 0
    var confirmingClear by rememberSaveable { mutableStateOf(false) }

    SectionTitle("History")
    SwitchRow("Save checks in History", checked = state.historyEnabled, onChange = settings::setHistoryEnabled)
    Text(
        if (state.historyEnabled) {
            "Your text and the suggestions are saved on this phone only."
        } else {
            "New checks aren't saved. Saved ones stay until you delete them."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kept?.let(::keptLabel).orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = count > 0, onClick = { confirmingClear = true }) { Text("Delete all") }
        TextButton(
            enabled = count > 0 && !settings.exporting,
            onClick = settings::exportHistory,
        ) { Text(if (settings.exporting) "Exporting…" else "Export") }
    }

    // Offered from this composition, so from the activity on screen now, and only once it's
    // in front: an export that finishes while the app is in the background waits, and is
    // dropped if that wait was more than a moment (see SettingsViewModel.takeExport).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val inFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val exported = settings.exported
    LaunchedEffect(exported, inFront) {
        if (exported == null || !inFront) return@LaunchedEffect
        settings.takeExport()?.let { share(context, it) }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Delete all checks?") },
            text = { Text("This deletes ${checks(count)} from this phone. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    settings.clearHistory()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "312 checks since 9 Oct". */
private fun keptLabel(kept: KeptHistory): String {
    val since = kept.since
    return if (kept.count == 0 || since == null) "No checks saved" else "${checks(kept.count)} since ${shortDate(since)}"
}

private fun checks(count: Int) = if (count == 1) "1 check" else "$count checks"

/** Offers the exported file through the share sheet. */
private fun share(context: Context, exported: Export) {
    val uri = when (exported) {
        is Export.Ready -> exported.uri
        Export.Failed -> {
            Toast.makeText(context, "Couldn't export history. Try again.", Toast.LENGTH_LONG).show()
            return
        }
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.openFromSettings(Intent.createChooser(send, "Export history"))
}
