package com.evanaronson.linguize.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Progress
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.DisclosureMotion
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.NavigationBackHandler
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.theme.Linguize
import com.evanaronson.linguize.ui.time.messageTimeLine

private val BubbleMax = 300.dp

/** Marks the writer bubble's check line, for tests of where it sits. */
internal const val CHECK_META_TAG = "lab_chat_check_meta"

/**
 * One message: the writer's on the right in ink, the partner's on the left on paper. A tap on
 * a bubble shows more of it in place: a partner bubble flips to its translation (tinted, with a
 * short crossfade) and back; a corrected writer bubble opens its changes under it, marked in
 * the typed text and listed one line each ([ChatCorrections]), and closes them again. How the writer's message was
 * checked sits at the bubble's bottom end, like a messaging app's time. The text is selectable
 * (long-press, with the system's Copy and other apps' actions); a long-press on the bubble's
 * edge opens Copy message (and Copy translation while it shows), and on the writer's own
 * message Edit message ([onEdit]; dimmed while [canEdit] is false, mid-turn). Lab, Time spent: a
 * clean writer message's time sits quietly at the foot of that menu (a corrected one's ends its
 * corrections block), when one was recorded. Screen readers get
 * the same as actions (Edit message only while it can be used), and the corrections' state.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    flipped: Flipped?,
    onFlip: () -> Unit,
    hint: Boolean,
    language: String? = null,
    onEdit: (() -> Unit)? = null,
    canEdit: Boolean = true,
) {
    val context = LocalContext.current
    val mine = message.fromWriter
    val corrections = hasCorrections(message)
    val open = corrections && expanded
    val shownText = (flipped as? Flipped.Text)?.translation
    val status = if (mine) checkStatus(message) else null
    val said = when {
        mine -> stringResource(R.string.lab_chat_you_said, message.text)
        shownText != null -> stringResource(R.string.lab_chat_translation_said, shownText)
        else -> stringResource(R.string.lab_chat_partner_said, message.text)
    }
    val spoken = listOfNotNull(said, status).joinToString(". ")
    val copyLabel = stringResource(R.string.lab_chat_copy)
    val copyTranslationLabel = stringResource(R.string.lab_chat_copy_translation)
    val editLabel = stringResource(R.string.lab_chat_edit_sent)
    // Only the writer's own messages can be edited.
    val edit = onEdit?.takeIf { mine }
    // One tap per bubble, named for what it does: flip a reply, or open a corrected message.
    val tapLabel = when {
        !mine -> stringResource(if (flipped != null) R.string.lab_chat_show_original else R.string.lab_chat_show_translation)
        corrections -> stringResource(if (open) R.string.lab_chat_corrections_hide else R.string.lab_chat_corrections_show)
        else -> null
    }
    val onTap: (() -> Unit)? = when {
        !mine -> onFlip
        corrections -> onToggle
        else -> null
    }
    val state = if (corrections) {
        stringResource(if (open) R.string.lab_expand_pattern_expanded else R.string.lab_expand_pattern_collapsed)
    } else null
    var menu by remember { mutableStateOf(false) }
    val copy = { context.copyToClipboard(message.text) }
    val tint = Linguize.colors.proof.copy(alpha = 0.08f)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp,
                ),
                color = when {
                    mine -> MaterialTheme.colorScheme.primary
                    flipped != null -> tint.compositeOver(Linguize.colors.card)
                    else -> Linguize.colors.card
                },
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                // The same highlight on both sides: a proof border while the bubble shows more of itself.
                border = when {
                    mine -> if (open) BorderStroke(2.dp, Linguize.colors.proof) else null
                    flipped != null -> BorderStroke(1.dp, Linguize.colors.proof.copy(alpha = 0.35f))
                    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier
                    .widthIn(max = BubbleMax)
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        state?.let { stateDescription = it }
                        if (onTap != null && tapLabel != null) onClick(tapLabel) { onTap(); true }
                        customActions = listOfNotNull(
                            if (onTap != null && tapLabel != null) CustomAccessibilityAction(tapLabel) { onTap(); true } else null,
                            CustomAccessibilityAction(copyLabel) { copy(); true },
                            shownText?.let { text -> CustomAccessibilityAction(copyTranslationLabel) { context.copyToClipboard(text); true } },
                            edit?.takeIf { canEdit }?.let { action -> CustomAccessibilityAction(editLabel) { action(); true } },
                        )
                    }
                    // A long-press on the text selects it; one on the bubble's edge opens the menu.
                    // A tap flips a reply, or opens (and closes) a corrected message's corrections.
                    .pointerInput(onTap) {
                        detectTapGestures(onTap = { onTap?.invoke() }, onLongPress = { menu = true })
                    },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Crossfade(flipped, Modifier.clearAndSetSemantics {}, animationSpec = tween(180), label = "translation") { side ->
                        when (side) {
                            null -> SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                            is Flipped.Text -> SelectionContainer {
                                Column {
                                    Text(side.translation, style = MaterialTheme.typography.bodyLarge)
                                    side.notes.forEach { note ->
                                        Spacer(Modifier.height(6.dp))
                                        Text(note, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Flipped.Loading -> Text(stringResource(R.string.lab_chat_translating), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            is Flipped.Failed -> Column {
                                Text(stringResource(side.failure.reason.titleResource), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.lab_chat_tap_to_retry), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    status?.let { CheckMeta(it, message.check == SentCheck.LooksGood, Modifier.align(Alignment.End)) }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(copyLabel) }, onClick = { menu = false; copy() })
                shownText?.let { text ->
                    DropdownMenuItem(text = { Text(copyTranslationLabel) }, onClick = { menu = false; context.copyToClipboard(text) })
                }
                edit?.let { action ->
                    DropdownMenuItem(text = { Text(editLabel) }, onClick = { menu = false; action() }, enabled = canEdit)
                }
                // Lab, Time spent: a clean message has no corrections block, so its time sits
                // here, under the actions, quiet (a corrected one shows it in its block).
                if (mine && !corrections) {
                    messageTimeLine(message.sessionId)?.let { time ->
                        RowDivider()
                        MessageTimeText(time, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (mine) {
            // The expand pattern's motion: grow from the top and fade in, shrink and fade out.
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(DisclosureMotion.EXPAND_MILLIS, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                    fadeIn(tween(DisclosureMotion.EXPAND_MILLIS)),
                exit = shrinkVertically(tween(DisclosureMotion.COLLAPSE_MILLIS)) + fadeOut(tween(DisclosureMotion.COLLAPSE_MILLIS)),
            ) {
                ChatCorrections(message, language)
            }
        } else if (hint && flipped == null) {
            Text(stringResource(R.string.lab_chat_tap_to_translate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
    }
}

/** The writer bubble's meta line, at its bottom end: quiet, like a messaging app's time. */
@Composable
private fun CheckMeta(text: String, looksGood: Boolean, modifier: Modifier = Modifier) {
    val quiet = LocalContentColor.current.copy(alpha = 0.72f)
    // Spoken as part of the bubble's description; the tag only marks where it sits.
    Row(modifier.padding(top = 4.dp).clearAndSetSemantics { testTag = CHECK_META_TAG }, verticalAlignment = Alignment.CenterVertically) {
        if (looksGood) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = quiet, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = quiet)
    }
}

/** The partner is writing. */
@Composable
internal fun TypingBubble() {
    val label = stringResource(R.string.lab_chat_typing)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = Linguize.colors.card,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text("•••", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).clearAndSetSemantics {})
        }
    }
}

/** The writer spoke last and no reply came: why, and Retry (or Open settings). */
@Composable
internal fun ReplyMissing(failure: CheckFailure?, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        if (failure != null) {
            Failure(failure.reason, failure.userMessage, null, onRetry, onOpenSettings)
        } else {
            Text(stringResource(R.string.lab_chat_no_reply), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Filled)
        }
    }
}

/** A local write failed; Retry reuses the checked message or generated answer. */
@Composable
internal fun ChatSaveFailure(message: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(message), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Filled)
    }
}

@Composable
internal fun ConversationSaveFailure(chat: ChatViewModel, conversationId: String? = null) {
    val failed = chat.conversationSaveFailure?.takeIf { conversationId == null || it.id == conversationId } ?: return
    val message = when (failed) {
        is ConversationWriteFailure.Create -> R.string.lab_chat_create_failed
        is ConversationWriteFailure.Rename -> R.string.lab_chat_rename_failed
        is ConversationWriteFailure.Delete -> R.string.lab_chat_delete_failed
        is ConversationWriteFailure.Rewind -> R.string.lab_chat_edit_failed
    }
    ChatSaveFailure(message, chat::retryConversationWrite)
}

/**
 * Editing a sent message would delete later messages of the writer's too: how many checks go,
 * and why, with Delete and Cancel. The app's confirmation dialog, as for deleting a conversation.
 */
@Composable
internal fun EditConfirmationDialog(confirmation: EditConfirmation, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(pluralStringResource(R.plurals.lab_chat_edit_confirm_title, confirmation.checks, confirmation.checks)) },
        text = { Text(stringResource(R.string.lab_chat_edit_confirm_body)) },
        confirmButton = { LinguizeButton(stringResource(R.string.lab_chat_delete), onConfirm, ButtonKind.Destructive) },
        dismissButton = { LinguizeButton(stringResource(R.string.lab_chat_cancel), onCancel, ButtonKind.Text) },
    )
}

/** Where the writer types. Send checks the message first. */
@Composable
internal fun Composer(chat: ChatViewModel) {
    val ink = MaterialTheme.colorScheme.onSurface
    val canSend = chat.draft.text.isNotBlank() && chat.thread != null
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Back from the review: the draft comes back focused, with the keyboard, as it was left.
    LaunchedEffect(chat.focusDraft) {
        if (chat.focusDraft) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
            chat.draftFocused()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 168.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Linguize.colors.card)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val label = stringResource(R.string.lab_chat_composer_label)
            val style = MaterialTheme.typography.bodyLarge.copy(color = ink)
            BasicTextField(
                value = chat.draft,
                onValueChange = chat::onDraftChange,
                textStyle = style,
                cursorBrush = SolidColor(ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
                decorationBox = { inner ->
                    Box {
                        if (chat.draft.text.isEmpty()) {
                            Text(stringResource(R.string.lab_chat_composer_placeholder), style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                        }
                        inner()
                    }
                },
            )
        }
        IconButton(onClick = chat::send, enabled = canSend, modifier = Modifier.padding(start = 4.dp).size(48.dp)) {
            Icon(
                painterResource(R.drawable.lab_chat_ic_send),
                contentDescription = stringResource(R.string.lab_chat_send_checked),
                tint = if (canSend) Linguize.colors.proof else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * The check before sending: the same card as for a message in another app, with its Replace
 * labelled Send. It is a step on top of the draft, so the way back is navigation, not a peer
 * of Send: Edit message with a back arrow above the card, and system Back, both return to the
 * draft with the keyboard. Reviewing is forced, accepting isn't: with nothing accepted, Send as
 * written under the card sends the text as typed. A failed or unclear check offers Retry (or
 * clarifying) and Edit message, never a way round it.
 */
@Composable
internal fun ReviewPanel(chat: ChatViewModel, check: CheckViewModel, maxHeight: Dp, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state = check.state
    val revision = ((state as? CardState.Done)?.result as? CheckResult.Reviewed)?.revision
    val actions = CardActions.of(
        check, context,
        onClose = { revision?.let(chat::sendReviewed) },
        onOpenSettings = onOpenSettings,
        applyLabel = R.string.lab_chat_send,
        phraseOrigin = null,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (chat.savingMessage) {
            Progress(stringResource(R.string.lab_chat_saving_message))
            return@Column
        }
        BackToDraft(chat::editDraft)
        if (chat.sendSaveFailed) {
            ChatSaveFailure(R.string.lab_chat_message_save_failed, chat::retrySend)
            return@Column
        }
        if (state != null) {
            // Weighted, so Send as written below keeps its place when the card takes the room.
            ResultCard(state, actions, Modifier.fillMaxWidth().heightIn(max = maxHeight).weight(1f, fill = false), scrollable = true)
        } else {
            Progress(stringResource(R.string.lab_chat_checking))
        }
        // With something accepted, the card's own row says "N changes accepted  Undo  Send".
        if (revision != null && !revision.canUndo) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                LinguizeButton(stringResource(R.string.lab_chat_send_as_written), { chat.sendReviewed(revision) }, ButtonKind.Filled)
            }
        }
    }
}

/**
 * The review's way back to the draft, at its top start where a back arrow goes: "Edit message".
 * System Back does the same while it shows (after closing the keyboard, if one is up).
 */
@Composable
internal fun BackToDraft(onBack: () -> Unit, modifier: Modifier = Modifier) {
    NavigationBackHandler(onBack = onBack)
    LinguizeButton(onBack, ButtonKind.Text, modifier, contentPadding = PaddingValues(start = 8.dp, end = 16.dp)) {
        Icon(painterResource(R.drawable.ic_back), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.lab_chat_corrections_edit_message))
    }
}
