package com.evanaronson.linguize.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.components.SectionTitle
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale

/** The save-history preference. Data controls belong to the full History page. */
@Composable
internal fun HistorySection(state: SettingsState, settings: SettingsViewModel) {
    SectionTitle(stringResource(R.string.history))
    SwitchRow(stringResource(R.string.save_history), checked = state.historyEnabled, onChange = settings::setHistoryEnabled)
    Text(
        if (state.historyEnabled) {
            stringResource(R.string.save_history_on)
        } else {
            stringResource(R.string.save_history_off)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Count, delete and export controls, shown only inside the full History page. */
@Composable
internal fun HistoryDataControls(settings: SettingsViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val kept by settings.history.collectAsState()
    val count = kept?.count ?: 0
    var confirmingClear by rememberSaveable { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kept?.let { keptLabel(resources, it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = count > 0, onClick = { confirmingClear = true }) { Text(stringResource(R.string.delete_all)) }
        TextButton(
            enabled = count > 0 && !settings.exporting,
            onClick = settings::exportHistory,
        ) { Text(if (settings.exporting) stringResource(R.string.exporting) else stringResource(R.string.export)) }
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
            title = { Text(stringResource(R.string.delete_all_question)) },
            text = { Text(pluralStringResource(R.plurals.delete_all_checks_description, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    settings.clearHistory()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.cancel)) } },
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

/** Count and date are formatted together so translations can reorder them. */
private fun keptLabel(resources: Resources, kept: KeptHistory): String {
    val since = kept.since
    return if (kept.count == 0 || since == null) resources.getString(R.string.no_checks_saved) else
        resources.getQuantityString(R.plurals.saved_checks_since, kept.count,
            kept.count, shortDate(since, locale = resources.uiLocale))
}

/** Offers the exported file through the share sheet. */
private fun share(context: Context, exported: Export) {
    val uri = when (exported) {
        is Export.Ready -> exported.uri
        Export.Failed -> {
            Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
            return
        }
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("", uri)
    context.openExternal(Intent.createChooser(send, context.getString(R.string.export_history)))
}
