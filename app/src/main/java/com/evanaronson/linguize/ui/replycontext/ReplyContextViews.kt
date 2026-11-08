package com.evanaronson.linguize.ui.replycontext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.InsetShape
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.readCopiedText
import com.evanaronson.linguize.ui.theme.Linguize

/** On the card, above the result: the attached message, compactly, with Remove. Nothing when none is attached. */
@Composable
internal fun ReplyContextHeader(actions: ReplyContextActions?) {
    val message = actions?.message ?: return
    ReplyingTo(message, actions.remove, Modifier.padding(bottom = 14.dp))
}

/**
 * On the card, below the result: a way to attach the message being replied to, once there is
 * a result to check again. Nothing when one is attached, while [hidden] (another card page),
 * or when the check can't run again.
 */
@Composable
internal fun ReplyContextFooter(actions: ReplyContextActions?, state: CardState, hidden: Boolean = false) {
    if (actions == null || actions.message != null || hidden || !offersReplyContext(state)) return
    ReplyContextEntry(actions.attach, Modifier.padding(top = 16.dp))
}

/** Try it: the message being replied to sits above the text box, attached or not. */
@Composable
internal fun TryItReplyContext(check: CheckViewModel, modifier: Modifier = Modifier) {
    val message = check.replyingTo
    if (message != null) {
        ReplyingTo(message, onRemove = { check.replyTo(null) }, modifier)
    } else {
        ReplyContextEntry(onAttach = { check.replyTo(it) }, modifier, hint = true)
    }
}

/** A result or a failure that can be retried; not before an answer or on a limited card. */
internal fun offersReplyContext(state: CardState): Boolean = when (state) {
    is CardState.Loading -> false
    is CardState.Failed -> state.reason != CheckFailure.Reason.TooLong && state.reason != CheckFailure.Reason.TooMany
    is CardState.Done -> state.result !is CheckResult.WrongLanguage
}

/** The attached message on a grey inset: two lines, the whole of it on tap, and Remove. */
@Composable
internal fun ReplyingTo(message: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(message) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.lab_reply_context_show_less else R.string.lab_reply_context_show_all)
    val removeLabel = stringResource(R.string.lab_reply_context_remove_description)
    Row(
        modifier
            .fillMaxWidth()
            .clip(InsetShape)
            .background(Linguize.colors.panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Text(
                stringResource(R.string.lab_reply_context_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (expanded) message else replyPreview(message),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinguizeButton(
            stringResource(R.string.lab_reply_context_remove),
            onRemove,
            ButtonKind.Destructive,
            Modifier.padding(end = 4.dp).semantics { contentDescription = removeLabel },
        )
    }
}

/**
 * Attaching a message: Use copied text attaches what was copied in one tap; Type it opens a
 * field. Copied text is read only on that tap, never watched.
 */
@Composable
internal fun ReplyContextEntry(onAttach: (String) -> Boolean, modifier: Modifier = Modifier, hint: Boolean = false) {
    val context = LocalContext.current
    var draft by rememberSaveable(stateSaver = ReplyDraft.Saver) { mutableStateOf(ReplyDraft()) }
    fun step(next: ReplyStep) {
        draft = when (next) {
            is ReplyStep.Attach -> if (onAttach(next.message)) ReplyDraft() else draft.copy(editing = true, notice = ReplyNotice.TooLong)
            is ReplyStep.Show -> next.draft
        }
    }
    val useCopied = { step(draft.usingClipboard(context.readCopiedText())) }
    Column(modifier.fillMaxWidth()) {
        if (!draft.editing) {
            Text(stringResource(R.string.lab_reply_context_entry), style = MaterialTheme.typography.labelLarge)
            if (hint) {
                Text(
                    stringResource(R.string.lab_reply_context_entry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LinguizeButton(stringResource(R.string.lab_reply_context_use_copied), useCopied, ButtonKind.Tonal)
                LinguizeButton(stringResource(R.string.lab_reply_context_type), { draft = draft.typing() }, ButtonKind.Text)
            }
        } else {
            val focus = remember { FocusRequester() }
            // The field appears only when the writer asked for it, so it can take focus.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val note = when (draft.notice) {
                ReplyNotice.NoCopiedText -> stringResource(R.string.lab_reply_context_no_copied)
                ReplyNotice.CopiedTextUnavailable -> stringResource(R.string.lab_reply_context_unavailable)
                ReplyNotice.TooLong -> null
                null -> null
            }
            val tooLong = draft.tooLong
            LinguizeTextField(
                value = draft.text,
                onValueChange = { draft = draft.typed(it) },
                label = stringResource(R.string.lab_reply_context_field),
                placeholder = stringResource(R.string.lab_reply_context_placeholder),
                supporting = if (tooLong) stringResource(R.string.lab_reply_context_too_long, ReplyPrompt.MAX_CHARS) else note,
                isError = tooLong,
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                fieldModifier = Modifier.focusRequester(focus),
            )
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LinguizeButton(stringResource(R.string.lab_reply_context_add), { step(draft.submitted()) },
                    ButtonKind.Tonal, enabled = draft.canAdd)
                if (draft.notice == null) {
                    LinguizeButton(stringResource(R.string.lab_reply_context_use_copied), useCopied, ButtonKind.Text)
                }
                LinguizeButton(stringResource(R.string.cancel), { draft = ReplyDraft() }, ButtonKind.Text)
            }
        }
    }
}
