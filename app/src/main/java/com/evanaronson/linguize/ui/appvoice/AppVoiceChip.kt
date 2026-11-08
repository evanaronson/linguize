package com.evanaronson.linguize.ui.appvoice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.VoiceSource
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LocalMenuState
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.text.labelResource

/**
 * "Formal, Gmail rule ▾" beside the language picker: says briefly that the source app's rule
 * applies, and opens a menu to change the register or suggestions for this check, or save
 * them as the app's rule. Shows nothing when no rule or card choice is in effect.
 * [onChanged] runs when the check should run again with the new voice.
 */
@Composable
fun AppVoiceChip(voice: CardVoiceViewModel, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    if (!voice.visible) return
    val app = voice.app ?: return
    val menu = LocalMenuState.current
    val owner = remember { Any() }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val open = menu.owner === owner
    DisposableEffect(menu, owner) {
        onDispose { if (menu.owner === owner) menu.dismiss() }
    }
    val appName = voice.appName ?: app
    val text = chipText(voice, appName)
    val description = stringResource(R.string.lab_app_voice_chip_description, text)
    LinguizeButton(
        onClick = {
            if (open) menu.dismiss() else menu.show(owner, anchor) {
                VoiceMenu(voice, appName, onChanged = {
                    menu.dismiss()
                    onChanged()
                }, onSaved = { menu.dismiss() })
            }
        },
        kind = ButtonKind.Outlined,
        modifier = modifier
            .onGloballyPositioned {
                anchor = it.boundsInRoot()
                menu.move(owner, anchor)
            }
            .semantics { contentDescription = description },
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(if (open) "▴" else "▾", Modifier.clearAndSetSemantics {})
    }
}

/** The strongest choice in effect, named with where it came from. */
@Composable
private fun chipText(voice: CardVoiceViewModel, appName: String): String {
    val resolved = voice.voice
    if (resolved.source == VoiceSource.ThisCheck) {
        val value = voice.override.register?.let { stringResource(it.labelResource) }
            ?: voice.override.judgments?.let { stringResource(it.labelResource) }.orEmpty()
        return stringResource(R.string.lab_app_voice_chip_this_check, value)
    }
    val rule = voice.rule
    val value = rule?.register?.let { stringResource(it.labelResource) }
        ?: rule?.judgments?.let { stringResource(it.labelResource) }
        ?: rule?.language?.displayName().orEmpty()
    return stringResource(R.string.lab_app_voice_chip_rule, value, appName)
}

@Composable
private fun VoiceMenu(voice: CardVoiceViewModel, appName: String, onChanged: () -> Unit, onSaved: () -> Unit) {
    val current = voice.voice
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            MenuHeading(stringResource(R.string.writing_register))
            Column(Modifier.selectableGroup()) {
                WritingRegister.entries.forEach { register ->
                    MenuOption(stringResource(register.labelResource), register == current.register) {
                        if (voice.chooseRegister(register)) onChanged()
                    }
                }
            }
            MenuHeading(stringResource(R.string.what_to_suggest))
            Column(Modifier.selectableGroup()) {
                Judgments.entries.forEach { judgments ->
                    MenuOption(stringResource(judgments.labelResource), judgments == current.judgments) {
                        if (voice.chooseJudgments(judgments)) onChanged()
                    }
                }
            }
            if (!voice.override.isEmpty) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.lab_app_voice_save, appName),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) {
                            voice.saveRule()
                            onSaved()
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun MenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(
            if (selected) "✓" else " ",
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
