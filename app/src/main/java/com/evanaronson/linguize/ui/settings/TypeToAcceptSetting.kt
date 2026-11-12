package com.evanaronson.linguize.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.components.SwitchRow

/** Lab, Type to accept: the switch under Checking. Off by default; see docs/lab/type-to-accept.md. */
@Composable
internal fun TypeToAcceptSwitch() {
    val context = LocalContext.current
    val preferences = remember(context) { TypeToAcceptPreferences(context) }
    var enabled by remember(preferences) { mutableStateOf(preferences.enabled) }
    SwitchRow(
        stringResource(R.string.lab_type_to_accept_title),
        stringResource(R.string.lab_type_to_accept_summary),
        checked = enabled,
        onChange = { enabled = it; preferences.enabled = it },
    )
}
