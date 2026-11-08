package com.evanaronson.linguize.ui.phrasebook

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.data.phrasebook.Phrase
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhraseSource
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.ChoiceRow
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.history.AppLabels
import com.evanaronson.linguize.ui.history.SwipeToDelete
import com.evanaronson.linguize.ui.openExternal
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.theme.Linguize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saved phrases by language: search, add one by hand, copy, delete with Undo, and export
 * them all as text. [onOpenCheck] opens the saved check a phrase came from.
 */
@Composable
fun PhrasebookScreen(model: PhrasebookViewModel, onOpenCheck: (String) -> Unit) {
    val state by model.state.collectAsState()
    val deleted by model.lastDeleted.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = rememberAppLabels(state?.sections.orEmpty().flatMap { it.phrases }.mapNotNull { it.app }.toSet())

    val deletedMessage = stringResource(R.string.lab_phrasebook_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(deleted) {
        val phrase = deleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) model.undoDelete() else model.deleteConfirmed(phrase)
    }

    Box(Modifier.fillMaxSize()) {
        ScreenColumn {
            val current = state ?: return@ScreenColumn
            if (current.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinguizeTextField(
                    current.query, model::search,
                    placeholder = stringResource(R.string.lab_phrasebook_search),
                    accessibleLabel = stringResource(R.string.lab_phrasebook_search),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            SectionHeader(pluralStringResource(R.plurals.lab_phrasebook_count, current.total, current.total)) {
                if (!adding) LinguizeButton(stringResource(R.string.lab_phrasebook_add), { adding = true }, ButtonKind.Text)
            }
            if (adding) {
                AddPhrase(
                    initialLanguage = current.sections.firstOrNull()?.language,
                    onSave = { draft ->
                        model.add(draft) { saved ->
                            if (saved) {
                                adding = false
                                Toast.makeText(context, R.string.lab_phrasebook_added, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCancel = { adding = false },
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                current.total == 0 -> SectionCard {
                    Quiet(stringResource(R.string.lab_phrasebook_empty), Modifier.padding(vertical = 14.dp))
                }
                current.matching == 0 -> SectionCard {
                    Quiet(stringResource(R.string.lab_phrasebook_no_match), Modifier.padding(vertical = 14.dp))
                }
            }
            current.sections.forEach { section ->
                key(section.language) {
                    SectionHeader(sectionTitle(section.language))
                    SectionCard {
                        section.phrases.forEachIndexed { index, phrase ->
                            key(phrase.id) {
                                if (index > 0) RowDivider()
                                SwipeToDelete(onDelete = { model.delete(phrase.id) }) {
                                    PhraseRow(
                                        phrase,
                                        appLabel = labels[phrase.app],
                                        expanded = expanded == phrase.id,
                                        onToggle = { expanded = if (expanded == phrase.id) null else phrase.id },
                                        onCopy = { context.copyToClipboard(phrase.phrase) },
                                        onDelete = { model.delete(phrase.id) },
                                        onOpenCheck = phrase.sessionId?.let { id -> { onOpenCheck(id) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (current.total > 0) {
                val exportLabels = exportLabels()
                Spacer(Modifier.height(16.dp))
                ListCard({
                    ListRow(
                        stringResource(R.string.lab_phrasebook_export),
                        stringResource(R.string.lab_phrasebook_export_summary),
                        onClick = { share(context, phrasebookText(model.allSections(), exportLabels)) },
                    )
                })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun sectionTitle(language: String?): String =
    Language.forCode(language)?.displayName() ?: stringResource(R.string.lab_phrasebook_other_language)

@Composable
private fun exportLabels(): PhraseTextLabels {
    val resources = LocalResources.current
    val title = stringResource(R.string.lab_phrasebook_export_heading)
    val names = Language.all.associate { it.code to it.displayName() }
    val other = stringResource(R.string.lab_phrasebook_other_language)
    return PhraseTextLabels(
        title = title,
        language = { names[it] ?: other },
        insteadOf = { resources.getString(R.string.lab_phrasebook_instead_of, it) },
    )
}

/** App names for the packages phrases came from, looked up off the main thread. */
@Composable
private fun rememberAppLabels(packages: Set<String>): Map<String?, String?> {
    val context = LocalContext.current
    val lookup = remember(context) { AppLabels(context.packageManager) }
    val labels by produceState<Map<String?, String?>>(emptyMap(), packages) {
        value = withContext(Dispatchers.IO) { packages.associateWith { lookup.of(it) } }
    }
    return labels
}

@Composable
private fun PhraseRow(
    phrase: Phrase,
    appLabel: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onOpenCheck: (() -> Unit)?,
) {
    val resources = LocalResources.current
    val deleteLabel = stringResource(R.string.lab_phrasebook_delete_phrase)
    val copyLabel = stringResource(R.string.lab_phrasebook_copy_phrase, phrase.phrase)
    val source = stringResource(when (phrase.source) {
        PhraseSource.Fix -> R.string.lab_phrasebook_from_fix
        PhraseSource.Rewording -> R.string.lab_phrasebook_from_rewording
        PhraseSource.Manual -> R.string.lab_phrasebook_from_you
    })
    val meta = listOfNotNull(source, appLabel, shortDate(phrase.createdAt, locale = resources.uiLocale))
        .joinToString(stringResource(R.string.status_separator))
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete background shows only while swiping.
            .background(Linguize.colors.card)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) { onCopy(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            }
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.lab_phrasebook_hide_actions else R.string.lab_phrasebook_show_actions),
                onClick = onToggle,
            )
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(phrase.phrase, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                phrase.replaced?.let {
                    Text(stringResource(R.string.lab_phrasebook_instead_of, it), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCopy) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = copyLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        phrase.meaning?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
        }
        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        if (expanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                onOpenCheck?.let { LinguizeButton(stringResource(R.string.lab_phrasebook_open_check), it, ButtonKind.Text) }
                LinguizeButton(stringResource(R.string.lab_phrasebook_delete), onDelete, ButtonKind.Destructive)
            }
        }
    }
}

/** Typing in a phrase heard or read elsewhere. */
@Composable
private fun AddPhrase(initialLanguage: String?, onSave: (PhraseDraft) -> Unit, onCancel: () -> Unit) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var replaced by rememberSaveable { mutableStateOf("") }
    var meaning by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(Language.forCode(initialLanguage)?.code ?: Language.all.first().code) }
    val draft = PhraseDraft(phrase, replaced, meaning, language, PhraseSource.Manual)
    val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        LinguizeTextField(phrase, { phrase = it.take(PhrasebookStore.MAX_PHRASE) },
            label = stringResource(R.string.lab_phrasebook_field_phrase),
            placeholder = stringResource(R.string.lab_phrasebook_field_phrase_hint), large = true, keyboardOptions = sentences)
        Spacer(Modifier.height(12.dp))
        LinguizeTextField(replaced, { replaced = it.take(PhrasebookStore.MAX_PHRASE) },
            label = stringResource(R.string.lab_phrasebook_field_replaced),
            placeholder = stringResource(R.string.lab_phrasebook_field_replaced_hint), keyboardOptions = sentences)
        Spacer(Modifier.height(12.dp))
        LinguizeTextField(meaning, { meaning = it },
            label = stringResource(R.string.lab_phrasebook_field_meaning),
            placeholder = stringResource(R.string.lab_phrasebook_field_meaning_hint), keyboardOptions = sentences)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lab_phrasebook_field_language), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            Language.all.forEach { option ->
                ChoiceRow(option.displayName(), null, selected = language == option.code, onClick = { language = option.code })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LinguizeButton(stringResource(R.string.cancel), onCancel, ButtonKind.Text)
            LinguizeButton(stringResource(R.string.lab_phrasebook_save), { onSave(draft) }, ButtonKind.Filled, enabled = draft.isValid)
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.openExternal(Intent.createChooser(send, context.getString(R.string.lab_phrasebook_export_chooser)))
}
