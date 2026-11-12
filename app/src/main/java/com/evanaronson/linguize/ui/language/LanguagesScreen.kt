package com.evanaronson.linguize.ui.language

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.language.Languages
import com.evanaronson.linguize.core.language.MyLanguages
import com.evanaronson.linguize.data.language.LanguageCatalogAsset
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.cardSegment
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Lab, lang-selector: the Languages page, the one place to choose the writer's languages. The
 * selector's **Add languages…** and Settings' **Languages** row both open it. A full page, not a
 * sheet: 183 rows, a search field and the keyboard need the whole height, and Back (which closes
 * the keyboard first) is how every page in the app is left.
 *
 * A search field, then **Your languages** in the selector's order (each but the first can move
 * up), **Suggested** (languages found in History that aren't added) and **Every language**,
 * A to Z. Checking a language adds it at once and unchecking removes it, so the selector is up
 * to date when the page closes; nothing saved in a language is deleted. The last language can't
 * be unchecked.
 */
@Composable
fun LanguagesScreen(language: AppLanguage) {
    val context = LocalContext.current
    val catalog = remember(context) { LanguageCatalogAsset.get(context) }
    val mine by language.mine.collectAsState()
    val suggested by language.suggested.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Settings can open this before Home has read the list and History.
    LaunchedEffect(language) { language.load() }
    val sections = remember(catalog, mine, suggested, query) { AddLanguages.sections(catalog, mine, suggested, query) }

    val lastMessage = stringResource(R.string.lab_lang_selector_last, brandName(mine.firstOrNull()))
    val fullMessage = stringResource(R.string.lab_lang_selector_full, MyLanguages.MAX)
    val toggle: (String) -> Unit = { code ->
        when (AddLanguages.toggle(mine, code)) {
            AddLanguages.Toggle.Add -> scope.launch { language.add(code) }
            AddLanguages.Toggle.Remove -> scope.launch { language.remove(code) }
            AddLanguages.Toggle.KeepLast -> Toast.makeText(context, lastMessage, Toast.LENGTH_LONG).show()
            AddLanguages.Toggle.Full -> Toast.makeText(context, fullMessage, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag(LANGUAGES_PAGE_TAG),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        item(key = "search") {
            Column {
                Spacer(Modifier.height(8.dp))
                LinguizeTextField(
                    query, { query = it },
                    placeholder = stringResource(R.string.lab_lang_selector_search),
                    accessibleLabel = stringResource(R.string.lab_lang_selector_search),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    fieldModifier = Modifier.testTag(LANGUAGES_SEARCH_TAG),
                )
                if (query.isBlank()) CardNote(stringResource(R.string.lab_lang_selector_note))
            }
        }
        sections.forEach { section ->
            item(key = "header:${section.kind}") {
                Column {
                    when (section.kind) {
                        AddLanguages.Kind.Yours -> SectionHeader(stringResource(R.string.lab_lang_selector_yours))
                        AddLanguages.Kind.Suggested -> {
                            SectionHeader(stringResource(R.string.lab_lang_selector_suggested))
                            Text(
                                stringResource(R.string.lab_lang_selector_suggested_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                            )
                        }
                        AddLanguages.Kind.Every -> SectionHeader(stringResource(R.string.lab_lang_selector_every))
                        AddLanguages.Kind.Results -> Spacer(Modifier.height(12.dp))
                    }
                    if (section.rows.isEmpty()) CardNote(stringResource(R.string.lab_lang_selector_no_results, query.trim()))
                }
            }
            itemsIndexed(section.rows, key = { _, row -> "${section.kind}:${row.code}" }) { index, row ->
                Column(Modifier.cardSegment(first = index == 0, last = index == section.rows.lastIndex)) {
                    if (index > 0) RowDivider()
                    LanguageRow(
                        code = row.code,
                        variety = language.varietyOf(row.code),
                        checked = row.checked,
                        onToggle = { toggle(row.code) },
                        onMoveUp = if (section.kind == AddLanguages.Kind.Yours && index > 0) {
                            { scope.launch { language.move(row.code, index - 1) } }
                        } else null,
                    )
                }
            }
        }
    }
}

/** One language: its circular icon, brand name, English and own name, and a checkbox. */
@Composable
private fun LanguageRow(code: String, variety: String?, checked: Boolean, onToggle: () -> Unit, onMoveUp: (() -> Unit)?) {
    val brand = brandName(code)
    val english = scopeLanguageName(code)
    val native = nativeName(code)
    val meaning = listOfNotNull(english.takeIf { it != brand }, native?.takeIf { it != english && it != brand })
        .joinToString(stringResource(R.string.list_separator))
    val spoken = stringResource(R.string.lab_lang_selector_row, brand, english, native ?: english)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .heightIn(min = 64.dp)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag("$LANGUAGES_ROW_TAG$code"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LanguageIcon(LanguageOption(code, variety), size = 32.dp)
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(brand, style = MaterialTheme.typography.bodyLarge)
                if (meaning.isNotEmpty()) {
                    Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Checkbox(checked = checked, onCheckedChange = null)
        }
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.testTag("$LANGUAGES_UP_TAG$code")) {
                Icon(
                    painterResource(R.drawable.lab_lang_selector_ic_up),
                    contentDescription = stringResource(R.string.lab_lang_selector_move_up, brand),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** The language's name in itself ("català", "日本語"), first letter capitalised for its locale. */
@Composable
private fun nativeName(code: String): String? =
    Languages.names.native(code)?.let { name ->
        val locale = Locale.forLanguageTag(code)
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

/** Settings' **Languages** row: the writer's languages by brand name; it opens [LanguagesScreen]. */
@Composable
fun LanguagesSettingsRow(language: AppLanguage, onOpen: () -> Unit) {
    val mine by language.mine.collectAsState()
    LaunchedEffect(language) { language.load() }
    val names = mine.map { brandName(it) }.joinToString(stringResource(R.string.list_separator))
    ListCard({ ListRow(stringResource(R.string.lab_lang_selector_title), names, onClick = onOpen) })
    Spacer(Modifier.height(8.dp))
}

internal const val LANGUAGES_PAGE_TAG = "lab_lang_selector_page"
internal const val LANGUAGES_SEARCH_TAG = "lab_lang_selector_search"
internal const val LANGUAGES_ROW_TAG = "lab_lang_selector_row:"
internal const val LANGUAGES_UP_TAG = "lab_lang_selector_up:"
