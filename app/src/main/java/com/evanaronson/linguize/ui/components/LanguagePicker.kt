package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.evanaronson.linguize.App
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.ui.language.LanguageIcon
import com.evanaronson.linguize.ui.language.LanguageOption
import com.evanaronson.linguize.ui.language.brandName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.evanaronson.linguize.ui.text.displayName

/**
 * "Auto-detect ▾": picks the language to check as, from auto-detect (null) and the writer's
 * languages ([options]; by default [rememberMyLanguages]), plus [selected] if it isn't one of
 * them. The host draws the menu over content in the same window, without changing the
 * trigger's layout or requiring a separate popup-window token.
 */
@Composable
fun LanguagePicker(
    selected: Language?,
    onSelect: (Language?) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Language> = rememberMyLanguages(),
) {
    val entries = (listOf<Language?>(null) + options + listOfNotNull(selected)).distinctBy { it?.code }
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
    val varieties = rememberVarieties()
    LinguizeButton(
        onClick = {
            if (open) menu.dismiss() else menu.show(owner, anchor) {
                LanguageMenu(entries, selected, varieties) { entry ->
                    menu.dismiss()
                    onSelect(entry)
                }
            }
        },
        kind = ButtonKind.Tonal,
        modifier = modifier
            .onGloballyPositioned {
                anchor = it.boundsInRoot()
                menu.move(owner, anchor)
            }
            .semantics { contentDescription = description },
    ) {
        PickerIcon(selected, varieties, Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(8.dp))
        Text(name)
        Spacer(Modifier.width(8.dp))
        Text(if (open) "▴" else "▾", Modifier.clearAndSetSemantics {})
    }
}

/** Lab, lang-selector: the selector's circular icon for [language] (Linguize's mark for Auto), with the writer's variety. */
@Composable
private fun PickerIcon(language: Language?, varieties: Map<String, String?>, modifier: Modifier = Modifier) {
    LanguageIcon(language?.let { LanguageOption(it.code, varieties[it.code]) }, modifier, size = 20.dp)
}

@Composable
private fun LanguageMenu(entries: List<Language?>, selected: Language?, varieties: Map<String, String?>, onSelect: (Language?) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = entry == selected, role = Role.RadioButton) { onSelect(entry) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PickerIcon(entry, varieties, Modifier.clearAndSetSemantics {})
                    Spacer(Modifier.width(12.dp))
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
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/**
 * Auto-detect is an action, distinct from the app's brand. Languages have the selector's brand
 * names (Lab, lang-selector: "Catalanize", "Gallicize", from the language catalog), else the
 * language's own name.
 */
@Composable
private fun label(language: Language?): String =
    if (language == null) stringResource(R.string.auto_detect) else brandName(language.code)

/** Lab, lang-selector: each writing profile's variety by language, for Castilianize's flag; empty until read. */
@Composable
private fun rememberVarieties(): Map<String, String?> {
    val context = LocalContext.current
    val varieties by produceState(emptyMap<String, String?>(), context) {
        withContext(Dispatchers.IO) {
            runCatching { App.of(context).settings.writingProfiles.associate { it.language to it.variety } }.getOrNull()
        }?.let { value = it }
    }
    return varieties
}

/**
 * Lab, language-generic: the writer's languages, read once off the main thread; Catalan and
 * Spanish until then (or where the app's settings can't be reached, as in a preview).
 */
@Composable
fun rememberMyLanguages(): List<Language> {
    val context = LocalContext.current
    val codes by produceState(LanguageScope.DEFAULT_LANGUAGES, context) {
        withContext(Dispatchers.IO) {
            runCatching { App.of(context).settings.myLanguages.codes }.getOrNull()
        }?.let { value = it }
    }
    return remember(codes) { codes.mapNotNull(Language::forCode) }
}
