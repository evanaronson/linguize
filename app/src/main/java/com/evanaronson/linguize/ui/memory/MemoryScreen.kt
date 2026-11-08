package com.evanaronson.linguize.ui.memory

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
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
import com.evanaronson.linguize.ui.components.SwitchRow
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.history.SwipeToDelete
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * "What Linguize knows": the switch, then the notes by category. Each note can be edited or
 * deleted (with Undo); the writer can add their own and forget everything.
 */
@Composable
fun MemoryScreen(model: MemoryViewModel) {
    val state by model.state.collectAsState()
    val deleted by model.lastDeleted.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingForget by rememberSaveable { mutableStateOf(false) }

    val deletedMessage = stringResource(R.string.lab_memory_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(deleted) {
        val fact = deleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) model.undoDelete() else model.deleteConfirmed(fact)
    }

    Box(Modifier.fillMaxSize()) {
        ScreenColumn {
            val current = state ?: return@ScreenColumn
            Spacer(Modifier.height(8.dp))
            ListCard({
                SwitchRow(
                    stringResource(R.string.lab_memory_switch),
                    stringResource(if (current.enabled) R.string.lab_memory_switch_on else R.string.lab_memory_switch_off),
                    current.enabled,
                    model::setEnabled,
                )
            })
            SectionHeader(pluralStringResource(R.plurals.lab_memory_count, current.total, current.total)) {
                if (!adding && current.total < MemoryState.MAX_FACTS) {
                    LinguizeButton(stringResource(R.string.lab_memory_add), { adding = true }, ButtonKind.Text)
                }
            }
            if (adding) {
                FactEditor(
                    initialText = "",
                    initialCategory = FactCategory.About,
                    onSave = { text, category ->
                        model.add(text, category) { saved ->
                            if (saved) {
                                adding = false
                                Toast.makeText(context, R.string.lab_memory_added, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCancel = { adding = false },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (current.total == 0 && !adding) {
                SectionCard { Quiet(stringResource(R.string.lab_memory_empty), Modifier.padding(vertical = 14.dp)) }
            }
            current.groups.forEach { group ->
                key(group.category) {
                    SectionHeader(stringResource(group.category.labelResource))
                    SectionCard {
                        group.facts.forEachIndexed { index, fact ->
                            key(fact.id) {
                                if (index > 0) RowDivider()
                                if (editing == fact.id) {
                                    FactEditor(
                                        initialText = fact.text,
                                        initialCategory = fact.category,
                                        onSave = { text, category ->
                                            model.edit(fact.id, text, category) { saved -> if (saved) editing = null }
                                        },
                                        onCancel = { editing = null },
                                        framed = false,
                                    )
                                } else {
                                    SwipeToDelete(onDelete = { model.delete(fact.id) }) {
                                        FactRow(
                                            fact,
                                            origin = originText(originOf(fact, current.sources)),
                                            expanded = expanded == fact.id,
                                            onToggle = { expanded = if (expanded == fact.id) null else fact.id },
                                            onEdit = { expanded = null; editing = fact.id },
                                            onDelete = { model.delete(fact.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (current.total > 0) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                    ListCard({
                        ListRow(
                            stringResource(R.string.lab_memory_forget_all),
                            stringResource(R.string.lab_memory_forget_all_summary),
                            onClick = { confirmingForget = true },
                        )
                    }, modifier = Modifier.padding(top = 16.dp))
                }
            }
            CardNote(stringResource(R.string.lab_memory_privacy))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text(stringResource(R.string.lab_memory_forget_all_question)) },
            text = { Text(stringResource(R.string.lab_memory_forget_all_description)) },
            confirmButton = {
                LinguizeButton(stringResource(R.string.lab_memory_forget_all_confirm), onClick = {
                    confirmingForget = false
                    expanded = null
                    editing = null
                    model.forgetEverything()
                }, kind = ButtonKind.Destructive)
            },
            dismissButton = { TextButton(onClick = { confirmingForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal val FactCategory.labelResource: Int
    get() = when (this) {
        FactCategory.About -> R.string.lab_memory_category_about
        FactCategory.People -> R.string.lab_memory_category_people
        FactCategory.Goals -> R.string.lab_memory_category_goals
        FactCategory.Preferences -> R.string.lab_memory_category_preferences
    }

@Composable
private fun originText(origin: FactOrigin): String {
    val locale = LocalResources.current.uiLocale
    return when (origin) {
        is FactOrigin.Writer -> stringResource(if (origin.edited) R.string.lab_memory_edited else R.string.lab_memory_from_you)
        is FactOrigin.One -> {
            val date = shortDate(origin.at, locale = locale)
            stringResource(
                when {
                    origin.chat && origin.edited -> R.string.lab_memory_from_chat_edited
                    origin.chat -> R.string.lab_memory_from_chat
                    origin.edited -> R.string.lab_memory_from_other_edited
                    else -> R.string.lab_memory_from_other
                },
                date,
            )
        }
        is FactOrigin.Many -> {
            val date = shortDate(origin.latest, locale = locale)
            pluralStringResource(
                if (origin.edited) R.plurals.lab_memory_from_chats_edited else R.plurals.lab_memory_from_chats,
                origin.count, origin.count, date,
            )
        }
    }
}

@Composable
private fun FactRow(
    fact: Fact,
    origin: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editLabel = stringResource(R.string.lab_memory_edit_note)
    val deleteLabel = stringResource(R.string.lab_memory_delete_note)
    Column(
        Modifier
            .fillMaxWidth()
            // Opaque, so the delete background shows only while swiping.
            .background(Linguize.colors.card)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(editLabel) { onEdit(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            }
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.lab_memory_hide_actions else R.string.lab_memory_show_actions),
                onClick = onToggle,
            )
            .padding(vertical = 12.dp),
    ) {
        Text(fact.text, style = MaterialTheme.typography.bodyLarge)
        Text(origin, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        if (expanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                LinguizeButton(stringResource(R.string.lab_memory_edit), onEdit, ButtonKind.Text)
                LinguizeButton(stringResource(R.string.lab_memory_delete), onDelete, ButtonKind.Destructive)
            }
        }
    }
}

/** Adding a note, or editing one in place ([framed] false, inside its group's card). */
@Composable
private fun FactEditor(
    initialText: String,
    initialCategory: FactCategory,
    onSave: (String, FactCategory) -> Unit,
    onCancel: () -> Unit,
    framed: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    val valid = text.trim().isNotEmpty() && text.trim().length <= MemoryState.MAX_FACT_CHARS
    val content: @Composable () -> Unit = {
        LinguizeTextField(
            text, { text = it.replace('\n', ' ').take(MemoryState.MAX_FACT_CHARS) },
            label = stringResource(R.string.lab_memory_field_note),
            placeholder = stringResource(R.string.lab_memory_field_note_hint),
            large = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lab_memory_field_category), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            FactCategory.entries.forEach { option ->
                ChoiceRow(stringResource(option.labelResource), null, selected = category == option, onClick = { category = option })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LinguizeButton(stringResource(R.string.cancel), onCancel, ButtonKind.Text)
            LinguizeButton(stringResource(R.string.save), { onSave(text, category) }, ButtonKind.Filled, enabled = valid)
        }
    }
    if (framed) {
        SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) { content() }
    } else {
        Column(Modifier.padding(vertical = 12.dp)) { content() }
    }
}
