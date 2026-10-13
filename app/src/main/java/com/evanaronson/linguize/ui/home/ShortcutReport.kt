package com.evanaronson.linguize.ui.home

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.ShortcutProbe
import java.text.DateFormat
import java.util.Date

/** Persistent evidence when Android does not display service toasts. Preview only. */
@Composable
internal fun ShortcutReport() {
    val context = LocalContext.current
    val prefs = remember(context) { ShortcutProbe.preferences(context) }
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val report = remember(prefs, revision) {
        listOf(prefs.getString("latest", "").orEmpty(), prefs.getString("previous", "").orEmpty())
    }
    val build = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }
    val connected = prefs.getLong("connected", 0)
    val received = prefs.getLong("received", 0)
    val time: (Long) -> String = { value ->
        if (value == 0L) context.getString(R.string.shortcut_probe_never)
        else DateFormat.getTimeInstance().format(Date(value))
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.shortcut_probe_title, build), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.shortcut_probe_connection, time(connected)))
            Text(stringResource(R.string.shortcut_probe_count, prefs.getInt("taps", 0), time(received)))
            Text(stringResource(R.string.shortcut_probe_latest, report[0]))
            Text(stringResource(R.string.shortcut_probe_previous, report[1]))
        }
    }
}
