package com.evanaronson.linguize.ui.chat

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.NewConversation
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.text.displayName
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.theme.Linguize
import java.util.Date

/** The chat's bar: conversations, the title, the language and Settings. The app's bottom bar holds the other places. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    title: String,
    /** Who the writer is talking to, when the conversation has someone; shown under the title. */
    persona: String?,
    language: String?,
    onOpenConversations: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            if (title.isEmpty()) {
                Image(
                    painterResource(R.drawable.wordmark),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(26.dp).semantics { heading() },
                )
            } else {
                Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    persona?.takeIf { it != title }?.let {
                        Text(stringResource(R.string.lab_chat_talking_to, it), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenConversations) {
                Icon(painterResource(R.drawable.lab_chat_ic_menu), contentDescription = stringResource(R.string.lab_chat_open_conversations))
            }
        },
        actions = {
            language?.let { LanguageButton(it, onSelectLanguage) }
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Linguize.colors.ground,
            scrolledContainerColor = Linguize.colors.ground,
        ),
    )
}

/** "ES ▾": the conversation's language, and a menu of the others. */
@Composable
private fun LanguageButton(code: String, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = languageDisplayName(code)
    val description = stringResource(R.string.lab_chat_language, name)
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { open = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(code.uppercase(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(4.dp))
            Text(if (open) "▴" else "▾", Modifier.clearAndSetSemantics {})
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Language.all.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName()) },
                    onClick = { open = false; onSelect(language.code) },
                    modifier = Modifier.semantics { selected = language.code == code },
                    trailingIcon = if (language.code == code) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

/** The list of conversations, newest first, with New, Rename and Delete. */
@Composable
internal fun ConversationDrawer(
    conversations: List<ConversationSummary>,
    currentId: String?,
    /** People memory knows the writer writes to, suggested in the new-conversation sheet. */
    people: List<String>,
    /** The open conversation's language, which the sheet starts with. */
    language: String?,
    onLoadPeople: () -> Unit,
    /** A new conversation: who the writer is talking to (null for the usual partner), and its language. */
    onNew: (persona: String?, language: String?) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by rememberSaveable { mutableStateOf(false) }
    ModalDrawerSheet(drawerContainerColor = Linguize.colors.ground) {
        Text(
            stringResource(R.string.lab_chat_conversations),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = { onLoadPeople(); starting = true })
                .heightIn(min = 56.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.lab_chat_ic_add), contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.lab_chat_new), style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider(color = Linguize.colors.rowDivider)
        LazyColumn {
            items(conversations, key = { it.conversation.id }) { summary ->
                ConversationRow(summary, summary.conversation.id == currentId, { onOpen(summary.conversation.id) },
                    onRename = { renaming = summary.conversation.id }, onDelete = { deleting = summary.conversation.id })
            }
        }
    }
    renaming?.let { id ->
        val current = conversations.firstOrNull { it.conversation.id == id }?.conversation?.title.orEmpty()
        RenameDialog(current, onDismiss = { renaming = null }, onSave = { onRename(id, it); renaming = null })
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.lab_chat_delete_title)) },
            text = { Text(stringResource(R.string.lab_chat_delete_body)) },
            confirmButton = {
                LinguizeButton(stringResource(R.string.lab_chat_delete), { onDelete(id); deleting = null }, ButtonKind.Destructive)
            },
            dismissButton = { LinguizeButton(stringResource(R.string.lab_chat_cancel), { deleting = null }, ButtonKind.Text) },
        )
    }
    if (starting) {
        NewConversationSheet(people, language, onDismiss = { starting = false }, onStart = { request ->
            starting = false
            onNew(request.persona, request.language)
        })
    }
}

/**
 * The new-conversation sheet: who you are talking to, optionally (someone memory knows, or
 * anyone you describe), and the language. Left empty, the usual partner starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationSheet(
    people: List<String>,
    language: String?,
    onDismiss: () -> Unit,
    onStart: (NewConversation) -> Unit,
) {
    var who by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf(language) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Linguize.colors.ground) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(stringResource(R.string.lab_chat_new), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            LinguizeTextField(who, { who = it.take(com.evanaronson.linguize.llm.chat.ChatPrompt.MAX_PERSONA) },
                label = stringResource(R.string.lab_chat_who_label),
                placeholder = stringResource(R.string.lab_chat_who_placeholder), singleLine = true)
            if (people.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    people.take(MAX_PEOPLE).forEach { person ->
                        LinguizeButton(person.take(40), { who = person.take(com.evanaronson.linguize.llm.chat.ChatPrompt.MAX_PERSONA) },
                            ButtonKind.Tonal)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.lab_chat_who_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.lab_chat_language_label), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.all.forEach { option ->
                    val selected = code == option.code
                    LinguizeButton(option.displayName(), { code = option.code },
                        if (selected) ButtonKind.Filled else ButtonKind.Outlined,
                        modifier = Modifier.semantics { this.selected = selected })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LinguizeButton(stringResource(R.string.lab_chat_cancel), onDismiss, ButtonKind.Text)
                LinguizeButton(stringResource(R.string.lab_chat_start), { onStart(NewConversation.of(who, code)) }, ButtonKind.Filled)
            }
        }
    }
}

private const val MAX_PEOPLE = 8

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    current: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val conversation = summary.conversation
    val title = conversation.title.ifEmpty { stringResource(R.string.lab_chat_untitled) }
    val language = languageDisplayName(conversation.language)
    val time = lastTime(conversation.updatedAt)
    val row = stringResource(R.string.lab_chat_row, title, language, time)
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (current) Modifier.semantics { selected = true } else Modifier)
            .clickable(onClick = onOpen)
            .heightIn(min = 64.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row }) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) Linguize.colors.proof else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.lab_chat_row_detail, language, time), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            summary.lastText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.lab_chat_ic_more), contentDescription = stringResource(R.string.lab_chat_more, title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.lab_chat_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lab_chat_delete), color = Linguize.colors.proof) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lab_chat_rename_title)) },
        text = {
            LinguizeTextField(title, { title = it.take(ChatViewModel.MAX_TITLE) }, label = stringResource(R.string.lab_chat_title_label),
                singleLine = true)
        },
        confirmButton = { LinguizeButton(stringResource(R.string.lab_chat_save), { onSave(title) }, ButtonKind.Filled) },
        dismissButton = { LinguizeButton(stringResource(R.string.lab_chat_cancel), onDismiss, ButtonKind.Text) },
    )
}

/** The time today, else the date. */
@Composable
private fun lastTime(at: Long): String {
    val context = LocalContext.current
    return if (DateUtils.isToday(at)) DateFormat.getTimeFormat(context).format(Date(at))
    else shortDate(at, locale = context.resources.uiLocale)
}

/** No key yet: the partner can't answer and nothing can be checked. */
@Composable
internal fun NoKeyStrip(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lab_chat_no_key_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.lab_chat_no_key_hint), style = MaterialTheme.typography.bodySmall)
        }
        LinguizeButton(stringResource(R.string.open_settings), onOpenSettings, ButtonKind.Text)
    }
}
