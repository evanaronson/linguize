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
import com.evanaronson.linguize.core.chat.ChatLabels
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.chat.NewConversation
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.components.AppNavigation
import com.evanaronson.linguize.ui.components.TopBar
import com.evanaronson.linguize.ui.language.LanguageFlag
import com.evanaronson.linguize.ui.language.LanguageIcon
import com.evanaronson.linguize.ui.language.LanguageOption
import com.evanaronson.linguize.ui.language.scopeLanguageName
import com.evanaronson.linguize.ui.text.languageDisplayName
import com.evanaronson.linguize.ui.theme.Linguize
import java.util.Date

/**
 * A conversation's bar: the app's page bar (Back to the list of conversations, the title,
 * and who the partner plays under it). Like every page opened from a tab, it has no
 * language selector (a conversation's language is fixed once created) and no Settings gear.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    /** Who the writer is talking to, when the conversation has someone; shown under the title. */
    persona: String?,
    onBack: () -> Unit,
) {
    AppNavigation(
        bar = TopBar.Page(
            title.ifEmpty { stringResource(R.string.lab_chat_untitled) },
            persona?.takeIf { it != title }?.let { stringResource(R.string.lab_chat_talking_to, it) },
        ),
        onBack = onBack,
        backLabel = stringResource(R.string.lab_chat_conversations),
        onOpenSettings = {},
        languageSelector = {},
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
    /** The app's current language, which the list shows and new conversations start in; null is Linguize (all). */
    language: String?,
    /** The selector's languages, for each row's flag under Linguize. */
    languageOptions: List<LanguageOption> = emptyList(),
    /** People memory knows the writer writes to, suggested in the new-conversation sheet. */
    people: List<String>,
    onLoadPeople: () -> Unit,
    /** A new conversation: who the writer is talking to (null for the usual partner), and its language (the app's). */
    onNew: (persona: String?, language: String?) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** No key yet: the list says so first, since no partner can answer (Lab, Chat tab IA). */
    needsKey: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
            if (needsKey) item(key = "no_key") { NoKeyStrip(onOpenSettings) }
            if (conversations.isEmpty()) item(key = "empty") {
                val name = language?.let { scopeLanguageName(it) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
                    Text(
                        if (name != null) stringResource(R.string.lab_language_scope_chat_empty, name) else stringResource(R.string.lab_consistency_chat_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.lab_language_scope_chat_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(conversations, key = { it.conversation.id }) { summary ->
                // Under Linguize the list mixes languages, so each row shows its flag.
                val flag = if (language == null) summary.conversation.language.let { code ->
                    languageOptions.firstOrNull { it.code == code } ?: LanguageOption(code)
                } else null
                ConversationRow(summary, flag, { onOpen(summary.conversation.id) },
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
 * anyone you describe). Left empty, the usual partner starts. There is no language choice
 * here (Lab, Consistency): a conversation starts in the app's language, or under Linguize
 * (Auto) in the chat's start language; a line says which, and the selector at the top changes it.
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Linguize.colors.ground) {
        // Back closes the keyboard first, then the sheet.
        com.evanaronson.linguize.ui.components.KeyboardBackHandler()
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
                placeholder = stringResource(R.string.lab_chat_who_placeholder),
                supporting = stringResource(R.string.lab_chat_who_note), singleLine = true)
            if (people.isNotEmpty()) {
                // Lab, Chat tab IA: the suggestions say what they are (memory's "People you write to").
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.lab_ia_chat_people), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    people.take(MAX_PEOPLE).forEach { person ->
                        LinguizeButton(person.take(40), { who = person.take(com.evanaronson.linguize.llm.chat.ChatPrompt.MAX_PERSONA) },
                            ButtonKind.Tonal)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguageIcon(language?.let { LanguageOption(it) })
                Spacer(Modifier.width(8.dp))
                Text(
                    if (language != null) stringResource(R.string.lab_consistency_new_in, scopeLanguageName(language))
                    else stringResource(R.string.lab_consistency_new_auto),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LinguizeButton(stringResource(R.string.lab_chat_cancel), onDismiss, ButtonKind.Text)
                LinguizeButton(stringResource(R.string.lab_chat_start), { onStart(NewConversation.of(who, null)) }, ButtonKind.Filled)
            }
        }
    }
}

private const val MAX_PEOPLE = 8

@Composable
private fun ConversationRow(
    summary: ConversationSummary,
    /** The conversation's language, shown as a flag when the list mixes languages. */
    flag: LanguageOption?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val conversation = summary.conversation
    val title = conversation.title.ifEmpty { stringResource(R.string.lab_chat_untitled) }
    val language = languageDisplayName(conversation.language)
    val time = lastTime(conversation.updatedAt)
    val persona = conversation.persona?.takeIf { it != title }?.let { stringResource(R.string.lab_chat_talking_to, it) }
    // Lab, Chat tab IA: whose turn it is. The writer's own last message reads "You: …".
    val last = summary.lastText?.let { if (summary.lastFromWriter) stringResource(R.string.lab_ia_chat_last_you, it) else it }
    // Everything the row shows, so a screen reader hears who and what too, not only the title.
    val row = ChatLabels.rowDescription(title, persona, language, time, last)
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
                flag?.let {
                    LanguageFlag(it)
                    Spacer(Modifier.width(8.dp))
                }
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            persona?.let {
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            last?.let {
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
