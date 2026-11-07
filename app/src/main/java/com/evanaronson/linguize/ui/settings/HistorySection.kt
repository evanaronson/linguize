package com.evanaronson.linguize.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.components.DISABLED_ALPHA
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.SwitchRow
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.openExternal

/** The save-history preference. Data controls belong to the full History page. */
@Composable
internal fun HistorySwitch(state: SettingsState, settings: SettingsViewModel) {
    ListCard({
        SwitchRow(
            stringResource(R.string.save_history),
            stringResource(if (state.historyEnabled) R.string.save_history_on else R.string.save_history_off),
            checked = state.historyEnabled,
            onChange = settings::setHistoryEnabled,
        )
    })
}

/** "12 checks since Oct 3": the History page's header. */
@Composable
internal fun keptHistoryTitle(settings: SettingsViewModel): String? {
    val resources = LocalResources.current
    val kept by settings.history.collectAsState()
    return kept?.let { keptLabel(resources, it) }
}

/** Export and Delete all, as the last card of the History page. */
@Composable
internal fun HistoryDataCard(settings: SettingsViewModel) {
    val context = LocalContext.current
    val kept by settings.history.collectAsState()
    val count = kept?.count ?: 0
    var confirmingClear by rememberSaveable { mutableStateOf(false) }

    ListCard(
        {
            val enabled = count > 0 && !settings.exporting
            ListRow(
                stringResource(R.string.export),
                stringResource(if (settings.exporting) R.string.exporting else R.string.export_summary),
                modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA),
                onClick = if (enabled) settings::exportHistory else null,
            )
        },
        {
            ListRow(
                stringResource(R.string.delete_all),
                stringResource(R.string.delete_all_summary),
                onClick = { confirmingClear = true },
            )
        },
        modifier = Modifier.padding(top = 16.dp),
    )

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
            text = { Text(stringResource(R.string.delete_all_history_description)) },
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
