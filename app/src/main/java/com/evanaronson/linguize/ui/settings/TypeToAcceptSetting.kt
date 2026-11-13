package com.evanaronson.linguize.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.lab.TypeToAcceptPreferences
import com.evanaronson.linguize.ui.components.SwitchRow

/**
 * Lab, Type to accept: the switch under Checking, off by default; see docs/lab/type-to-accept.md.
 * While it is on, two switches nested under it say how a correct change is felt and heard:
 * Vibrate when correct (on by default) and Sound when correct (off by default).
 */
@Composable
internal fun TypeToAcceptSwitch() {
    val context = LocalContext.current
    val preferences = remember(context) { TypeToAcceptPreferences(context) }
    var enabled by remember(preferences) { mutableStateOf(preferences.enabled) }
    var vibrate by remember(preferences) { mutableStateOf(preferences.vibrate) }
    var sound by remember(preferences) { mutableStateOf(preferences.sound) }
    Column {
        SwitchRow(
            stringResource(R.string.lab_type_to_accept_title),
            stringResource(R.string.lab_type_to_accept_summary),
            checked = enabled,
            onChange = { enabled = it; preferences.enabled = it },
        )
        AnimatedVisibility(enabled) {
            Column(Modifier.padding(start = 16.dp)) {
                SwitchRow(
                    stringResource(R.string.lab_type_to_accept_vibrate),
                    null,
                    checked = vibrate,
                    onChange = { vibrate = it; preferences.vibrate = it },
                )
                SwitchRow(
                    stringResource(R.string.lab_type_to_accept_sound),
                    stringResource(R.string.lab_type_to_accept_sound_summary),
                    checked = sound,
                    onChange = { sound = it; preferences.sound = it },
                )
            }
        }
    }
}
