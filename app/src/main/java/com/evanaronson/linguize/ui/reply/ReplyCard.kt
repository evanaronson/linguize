package com.evanaronson.linguize.ui.reply

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntent
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Loading
import com.evanaronson.linguize.ui.card.PageHeader
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * Lab: Help me reply, as a page of the understand card. The writer says what they want to
 * answer (or picks a suggestion), then reads the reply sentence by sentence with what each
 * says in their language, edits it if they like, and copies it or puts it back.
 */
@Composable
fun ReplyCard(
    reply: ReplyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    /** Null where there is no field to put the reply in; Copy is always offered. */
    onPutBack: ((String) -> Unit)? = null,
) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onBack)
    ResultSurface(modifier, scrollable = scrollable, navigationKey = reply.state::class) {
        PageHeader(stringResource(R.string.lab_reply_drafter_help_me_reply), onBack)
        ReplyContent(reply, onOpenSettings, onPutBack)
    }
}

/** The page's content without its surface, for hosts that have their own. */
@Composable
fun ReplyContent(
    reply: ReplyViewModel,
    onOpenSettings: (() -> Unit)? = null,
    onPutBack: ((String) -> Unit)? = null,
) {
    reply.message?.let { message ->
        Text(stringResource(R.string.lab_reply_drafter_replying_to), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            message.text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
    }
    when (val state = reply.state) {
        ReplyState.Describing -> Describing(reply)
        is ReplyState.Drafting -> Loading(state.intent, stringResource(R.string.lab_reply_drafter_drafting))
        is ReplyState.Drafted -> Drafted(reply, state.draft, onPutBack)
        is ReplyState.Failed -> {
            Failure(state.failure.reason, state.failure.userMessage, state.failure.detail, reply::retry, onOpenSettings)
            Spacer(Modifier.height(4.dp))
            LinguizeButton(stringResource(R.string.lab_reply_drafter_say_else), reply::describeAgain, ButtonKind.Text)
        }
    }
}

@Composable
private fun Describing(reply: ReplyViewModel) {
    var blank by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.lab_reply_drafter_what_to_say), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LinguizeTextField(
        value = reply.intent,
        onValueChange = {
            reply.changeIntent(it)
            blank = false
        },
        placeholder = stringResource(R.string.lab_reply_drafter_intent_placeholder),
        accessibleLabel = stringResource(R.string.lab_reply_drafter_intent_label),
        supporting = if (blank) stringResource(R.string.lab_reply_drafter_no_intent) else null,
        isError = blank,
        large = true,
        minLines = 2,
    )
    Spacer(Modifier.height(12.dp))
    LinguizeButton(stringResource(R.string.lab_reply_drafter_write), { blank = !reply.draftTyped() }, ButtonKind.Filled)
    when (val suggestions = reply.suggestions) {
        ReplySuggestions.Loading -> {
            Spacer(Modifier.height(16.dp))
            Quiet(stringResource(R.string.lab_reply_drafter_suggestions_loading))
        }
        is ReplySuggestions.Ready -> {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.lab_reply_drafter_suggestions), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Suggestions(suggestions.intents, reply::choose)
        }
        ReplySuggestions.Unavailable -> Unit
    }
}

@Composable
private fun Suggestions(intents: List<ReplyIntent>, onChoose: (ReplyIntent) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (intent in intents) {
            LinguizeButton(intent.label, { onChoose(intent) }, ButtonKind.Outlined)
        }
    }
}

@Composable
private fun Drafted(reply: ReplyViewModel, draft: ReplyDraft, onPutBack: ((String) -> Unit)?) {
    val context = LocalContext.current
    var editing by rememberSaveable(draft) { mutableStateOf(false) }
    val text = reply.reply ?: draft.text
    Text(stringResource(R.string.lab_reply_drafter_your_reply), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (editing) {
        LinguizeTextField(
            value = text,
            onValueChange = reply::edit,
            accessibleLabel = stringResource(R.string.lab_reply_drafter_edit_label),
            large = true,
            minLines = 2,
        )
    } else if (reply.isEdited) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().panel(Linguize.colors.panel))
        }
    }
    if (editing || reply.isEdited) {
        if (reply.isEdited) {
            Spacer(Modifier.height(8.dp))
            Quiet(stringResource(R.string.lab_reply_drafter_edited_note))
            LinguizeButton(stringResource(R.string.lab_reply_drafter_undo_edit), reply::undoEdit, ButtonKind.Text)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.lab_reply_drafter_draft_by_sentence), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
    } else {
        Text(
            stringResource(R.string.lab_reply_drafter_reply_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
    Glossed(draft)
    if (draft.words.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lab_reply_drafter_words), style = MaterialTheme.typography.labelLarge)
        for (word in draft.words) {
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Column {
                    Text(word.word, style = MaterialTheme.typography.bodyLarge)
                    Text(word.meaning, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ready = text.isNotBlank()
        if (onPutBack != null) {
            val action = stringResource(R.string.lab_reply_drafter_put_back_action)
            LinguizeButton(
                stringResource(R.string.lab_reply_drafter_put_back), { onPutBack(text) }, ButtonKind.Filled,
                enabled = ready,
                modifier = Modifier.semantics { contentDescription = action },
            )
        }
        LinguizeButton(
            stringResource(R.string.lab_reply_drafter_copy), { context.copyToClipboard(text) },
            if (onPutBack != null) ButtonKind.Tonal else ButtonKind.Filled, enabled = ready,
        )
        LinguizeButton(
            stringResource(if (editing) R.string.lab_reply_drafter_done_editing else R.string.lab_reply_drafter_edit),
            { editing = !editing }, ButtonKind.Text,
        )
        LinguizeButton(stringResource(R.string.lab_reply_drafter_simpler), reply::simpler, ButtonKind.Text)
        LinguizeButton(stringResource(R.string.lab_reply_drafter_say_else), reply::describeAgain, ButtonKind.Text)
    }
}

/** The draft: each sentence as it will be sent, with what it says underneath. */
@Composable
private fun Glossed(draft: ReplyDraft) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth().panel(Linguize.colors.panel)) {
            draft.sentences.forEachIndexed { index, sentence ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Text(sentence.text, style = MaterialTheme.typography.bodyLarge)
                Text(sentence.gloss, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun Modifier.panel(color: Color): Modifier = clip(InsetShape).background(color).padding(12.dp)

/** The way from an understood message into Help me reply. */
@Composable
fun HelpMeReplyButton(onClick: () -> Unit) {
    Spacer(Modifier.height(20.dp))
    LinguizeButton(stringResource(R.string.lab_reply_drafter_help_me_reply), onClick, ButtonKind.Filled)
}
