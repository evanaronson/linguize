package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.ui.text.displayName

/**
 * "Auto Detect ▾": picks the language to check as, from auto-detect (null) and every language
 * in [Language.all]. The host draws the menu over content in the same window, without
 * changing the trigger's layout or requiring a separate popup-window token.
 */
@Composable
fun LanguagePicker(selected: Language?, onSelect: (Language?) -> Unit, modifier: Modifier = Modifier) {
    val menu = LocalMenuState.current
    val owner = remember { Any() }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val open = menu.owner === owner
    DisposableEffect(menu, owner) {
        onDispose { if (menu.owner === owner) menu.dismiss() }
    }
    val name = label(selected)
    val description = stringResource(R.string.language_accessibility, name,
        selected?.displayName() ?: stringResource(R.string.detect_language))
    FilledTonalButton(
        onClick = {
            if (open) menu.dismiss() else menu.show(owner, anchor) {
                LanguageMenu(selected) { entry ->
                    menu.dismiss()
                    onSelect(entry)
                }
            }
        },
        modifier = modifier.heightIn(min = 48.dp)
            .onGloballyPositioned {
                anchor = it.boundsInRoot()
                menu.move(owner, anchor)
            }
            .semantics { contentDescription = description },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(name)
        Spacer(Modifier.width(8.dp))
        Text(if (open) "▴" else "▾", Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun LanguageMenu(selected: Language?, onSelect: (Language?) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            (listOf(null) + Language.all).forEach { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = entry == selected, role = Role.RadioButton) { onSelect(entry) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label(entry), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry?.displayName() ?: stringResource(R.string.detect_language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        if (entry == selected) "✓" else " ",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/**
 * Auto-detect is an action, distinct from the app's brand. Named languages keep their
 * selection-menu labels, or use the language's own name when it has no such label.
 */
@Composable
private fun label(language: Language?): String {
    val named = when (language) {
        null -> R.string.auto_detect
        Language.Catalan -> R.string.menu_catalan
        Language.Spanish -> R.string.menu_castilian
        else -> null
    }
    return named?.let { stringResource(it) } ?: language?.displayName().orEmpty()
}
