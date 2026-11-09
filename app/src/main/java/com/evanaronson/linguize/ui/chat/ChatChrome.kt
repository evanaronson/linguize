package com.evanaronson.linguize.ui.chat

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExtendedFloatingActionButton
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
import com.evanaronson.linguize.ui.components.FilterDropdown
import com.evanaronson.linguize.ui.language.LanguageFlag
import com.evanaronson.linguize.ui.language.LanguageOption
import com.evanaronson.linguize.ui.language.scopeLanguageName
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.theme.Linguize
import java.util.Date

/**
 * A conversation's bar: Back to the list of conversations, the title (and who the partner
 * plays), the app's language switch and Settings. There is no drawer: the list is the Chat
 * tab's own top level, one Back away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    title: String,
    /** Who the writer is talking to, when the conversation has someone; shown under the title. */
    persona: String?,
    onBack: () -> Unit,
    languageSwitch: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(title.ifEmpty { stringResource(R.string.lab_chat_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge)
                persona?.takeIf { it != title }?.let {
                    Text(stringResource(R.string.lab_chat_talking_to, it), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back_to, stringResource(R.string.lab_chat_conversations)))
            }
        },
        actions = {
            languageSwitch()
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

/**
 * The Chat tab's top level: the conversations in the current language, newest first, each
 * with its title, last message and time; Rename and Delete from a row's menu; and New
 * conversation, the main action, at the bottom.
 */
@Composable
internal fun ConversationList(
    conversations: List<ConversationSummary>,
    /** The app's current language, which the list shows and the sheet starts with. */
    language: String?,
    /** People memory knows the writer writes to, suggested in the new-conversation sheet. */
    people: List<String>,
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
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
            if (conversations.isEmpty()) item(key = "empty") {
                val name = language?.let { scopeLanguageName(it) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
                    Text(
                        if (name != null) stringResource(R.string.lab_language_scope_chat_empty, name) else stringResource(R.string.lab_chat_conversations),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.lab_language_scope_chat_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(conversations, key = { it.conversation.id }) { summary ->
                ConversationRow(summary, { onOpen(summary.conversation.id) },
                    onRename = { renaming = summary.conversation.id }, onDelete = { deleting = summary.conversation.id })
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onLoadPeople(); starting = true },
            icon = { Icon(painterResource(R.drawable.lab_chat_ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.lab_chat_new)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
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
    var code by rememberSaveable { mutableStateOf(language?.takeIf { Language.forCode(it) != null }) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lab_chat_language_label), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                val codes = Language.all.map { it.code }
                FilterDropdown(
                    selected = code?.takeIf { it in codes } ?: codes.first(),
                    options = codes,
                    label = { scopeLanguageName(it) },
                    description = stringResource(R.string.lab_chat_language_label),
                    onSelect = { code = it },
                    leading = { LanguageFlag(LanguageOption(it)) },
                )
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
            .clickable(onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = row }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            conversation.persona?.takeIf { it != title }?.let {
                Text(stringResource(R.string.lab_chat_talking_to, it), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            summary.lastText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
