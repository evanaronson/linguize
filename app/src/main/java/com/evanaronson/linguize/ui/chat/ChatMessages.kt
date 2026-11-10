package com.evanaronson.linguize.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SendState
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Progress
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.card.ReviewActions
import com.evanaronson.linguize.ui.card.visibleValue
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.DisclosureChevron
import com.evanaronson.linguize.ui.components.disclosureToggle
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize

private val BubbleMax = 300.dp

/**
 * One message: the writer's on the right in ink, the partner's on the left on paper. A tap on
 * a partner bubble flips it to its translation (tinted, with a short crossfade) and back. The
 * text is selectable (long-press, with the system's Copy and other apps' actions); a long-press
 * on the bubble's edge opens Copy message (and Copy translation while it shows). Screen readers
 * get the same as actions.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    flipped: Flipped?,
    onFlip: () -> Unit,
    hint: Boolean,
) {
    val context = LocalContext.current
    val mine = message.fromWriter
    val shownText = (flipped as? Flipped.Text)?.translation
    val spoken = when {
        mine -> stringResource(R.string.lab_chat_you_said, message.text)
        shownText != null -> stringResource(R.string.lab_chat_translation_said, shownText)
        else -> stringResource(R.string.lab_chat_partner_said, message.text)
    }
    val copyLabel = stringResource(R.string.lab_chat_copy)
    val copyTranslationLabel = stringResource(R.string.lab_chat_copy_translation)
    val flipLabel = stringResource(if (flipped != null) R.string.lab_chat_show_original else R.string.lab_chat_show_translation)
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
                border = if (mine) null else BorderStroke(1.dp,
                    if (flipped != null) Linguize.colors.proof.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .widthIn(max = BubbleMax)
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        customActions = listOfNotNull(
                            if (!mine) CustomAccessibilityAction(flipLabel) { onFlip(); true } else null,
                            CustomAccessibilityAction(copyLabel) { copy(); true },
                            shownText?.let { text -> CustomAccessibilityAction(copyTranslationLabel) { context.copyToClipboard(text); true } },
                        )
                    }
                    // A long-press on the text selects it; one on the bubble's edge opens the menu. A tap flips a reply.
                    .pointerInput(mine) {
                        detectTapGestures(onTap = { if (!mine) onFlip() }, onLongPress = { menu = true })
                    },
            ) {
                Crossfade(targetState = flipped, animationSpec = tween(180), label = "translation") { side ->
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics {}) {
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
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(copyLabel) }, onClick = { menu = false; copy() })
                shownText?.let { text ->
                    DropdownMenuItem(text = { Text(copyTranslationLabel) }, onClick = { menu = false; context.copyToClipboard(text) })
                }
            }
        }
        if (mine) {
            CheckMark(message, expanded, onToggle)
            if (expanded && message.changes.isNotEmpty()) Changes(message)
        } else if (hint && flipped == null) {
            Text(stringResource(R.string.lab_chat_tap_to_translate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
    }
}

/** Under a sent message: how it was checked. A corrected one opens to show what changed. */
@Composable
private fun CheckMark(message: ChatMessage, expanded: Boolean, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    when (message.check) {
        SentCheck.None -> Unit
        SentCheck.LooksGood -> Row(Modifier.padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.lab_chat_looks_good), style = style, color = muted)
        }
        SentCheck.AsWritten -> Text(stringResource(R.string.lab_chat_as_written), style = style, color = muted,
            modifier = Modifier.padding(top = 4.dp, end = 4.dp))
        SentCheck.Corrected -> {
            val count = message.changes.size
            val label = pluralStringResource(R.plurals.lab_chat_changes_count, count, count)
            Row(
                Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .disclosureToggle(expanded, { onToggle() },
                        stringResource(R.string.lab_chat_show_changes), stringResource(R.string.lab_chat_hide_changes))
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = style, color = Linguize.colors.proof)
                Spacer(Modifier.width(4.dp))
                DisclosureChevron(expanded, size = 16)
            }
        }
    }
}

/** What the writer typed, with the corrected parts marked, and each change in words. */
@Composable
private fun Changes(message: ChatMessage) {
    val proof = Linguize.colors.proof
    val raw = buildAnnotatedString {
        var pos = 0
        for (change in message.changes.sortedBy { it.start }) {
            if (change.start < pos) continue
            append(message.raw.substring(pos, change.start))
            withStyle(SpanStyle(color = proof, textDecoration = TextDecoration.Underline)) {
                append(message.raw.substring(change.start, change.end).ifEmpty { "‸" })
            }
            pos = change.end
        }
        append(message.raw.substring(pos))
    }
    Column(
        Modifier
            .padding(top = 4.dp)
            .widthIn(max = BubbleMax)
            .clip(RoundedCornerShape(16.dp))
            .background(Linguize.colors.card)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.lab_chat_you_wrote), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(raw, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = message.raw })
        }
        message.changes.forEach { change ->
            Spacer(Modifier.height(8.dp))
            Text(changeText(change.from, change.to), style = MaterialTheme.typography.bodyMedium,
                color = if (change.kind == EditKind.Fix) proof else MaterialTheme.colorScheme.onSurface)
            change.why?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun changeText(from: String, to: String): String = when {
    from.isEmpty() -> stringResource(R.string.lab_chat_change_add, to.visibleValue())
    to.isEmpty() -> stringResource(R.string.lab_chat_change_remove, from.visibleValue())
    else -> stringResource(R.string.lab_chat_change_replace, from.visibleValue(), to.visibleValue())
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

/** Where the writer types. Send checks the message first. */
@Composable
internal fun Composer(chat: ChatViewModel) {
    val ink = MaterialTheme.colorScheme.onSurface
    val canSend = chat.draft.text.isNotBlank() && chat.thread != null
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
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
 * labelled Send. Reviewing is forced, accepting isn't: with nothing accepted, Send as written
 * under the card sends the text as typed. A failed or unclear check offers Retry (or clarifying) and
 * Keep editing, never a way round it.
 */
@Composable
internal fun ReviewPanel(chat: ChatViewModel, check: CheckViewModel, maxHeight: Dp, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state = check.state
    val revision = ((state as? CardState.Done)?.result as? CheckResult.Reviewed)?.revision
    val actions = CardActions(
        onCopy = { text, kind -> context.copyToClipboard(text); check.copied(kind) },
        review = ReviewActions(
            onAccept = check::accept,
            onAcceptAll = { check.acceptAll(it) },
            onUndo = check::undo,
            onApply = { revision?.let(chat::sendReviewed) },
            applyLabel = R.string.lab_chat_send,
        ),
        onRetry = check::recheck,
        onOpenSettings = onOpenSettings,
        onSettle = check::settle,
        onRemoveChoice = check::removeChoice,
        onClarify = check::clarifyMeaning,
        onRemoveClarification = check::removeMeaning,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (state != null) {
            ResultCard(state, actions, Modifier.fillMaxWidth().heightIn(max = maxHeight), scrollable = true)
        } else {
            Progress(stringResource(R.string.lab_chat_checking))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            LinguizeButton(stringResource(R.string.lab_chat_keep_editing), chat::keepEditing, ButtonKind.Text)
            // With something accepted, the card's own row says "N changes accepted  Undo  Send".
            if (revision != null && !revision.canUndo) {
                LinguizeButton(stringResource(R.string.lab_chat_send_as_written), { chat.sendReviewed(revision) }, ButtonKind.Filled)
            }
        }
    }
}
