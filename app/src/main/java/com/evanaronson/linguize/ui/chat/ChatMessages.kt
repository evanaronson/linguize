package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.stateDescription
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
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.text.titleResource
import com.evanaronson.linguize.ui.theme.Linguize

private val BubbleMax = 300.dp

/**
 * One message: the writer's on the right in ink, the partner's on the left on paper. The text
 * is selectable (long-press, with the system's Copy and other apps' actions); a long-press on
 * the bubble's edge, or the screen reader's actions, offer Copy and, for the partner, Translate.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    translation: Translation?,
    translationOpen: Boolean,
    onTranslate: () -> Unit,
) {
    val context = LocalContext.current
    val mine = message.fromWriter
    val spoken = stringResource(if (mine) R.string.lab_chat_you_said else R.string.lab_chat_partner_said, message.text)
    val copyLabel = stringResource(R.string.lab_chat_copy)
    val translateLabel = stringResource(if (translationOpen) R.string.lab_chat_hide_translation else R.string.lab_chat_translate)
    var menu by remember { mutableStateOf(false) }
    val copy = { context.copyToClipboard(message.text) }
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
                color = if (mine) MaterialTheme.colorScheme.primary else Linguize.colors.card,
                contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .widthIn(max = BubbleMax)
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(copyLabel) { copy(); true },
                            if (!mine) CustomAccessibilityAction(translateLabel) { onTranslate(); true } else null,
                        )
                    }
                    // A long-press on the text selects it; one on the bubble's edge opens the menu.
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) },
            ) {
                SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics {})
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(copyLabel) }, onClick = { menu = false; copy() })
                if (!mine) DropdownMenuItem(text = { Text(translateLabel) }, onClick = { menu = false; onTranslate() })
            }
        }
        if (mine) {
            CheckMark(message, expanded, onToggle)
            if (expanded && message.changes.isNotEmpty()) Changes(message)
        } else {
            TranslateRow(translation, translationOpen, onTranslate)
        }
    }
}

/** Under a partner message: a quiet Translate, and the translation once asked for. */
@Composable
private fun TranslateRow(translation: Translation?, open: Boolean, onTranslate: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        stringResource(if (open) R.string.lab_chat_hide_translation else R.string.lab_chat_translate),
        style = MaterialTheme.typography.labelMedium,
        color = muted,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTranslate)
            .heightIn(min = 32.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
    when (translation) {
        null -> Unit
        Translation.Loading -> Text(stringResource(R.string.lab_chat_translating), style = MaterialTheme.typography.bodySmall, color = muted,
            modifier = Modifier.padding(horizontal = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
        is Translation.Failed -> Column(Modifier.padding(horizontal = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(translation.failure.reason.titleResource), style = MaterialTheme.typography.bodySmall, color = muted)
            LinguizeButton(stringResource(R.string.retry), onTranslate, ButtonKind.Text)
        }
        is Translation.Shown -> Column(
            Modifier
                .widthIn(max = BubbleMax)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            SelectionContainer {
                Column {
                    Text(translation.understood.translation, style = MaterialTheme.typography.bodyMedium)
                    translation.understood.notes.take(MAX_NOTES).forEach { note ->
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.lab_chat_note, note.quote, note.explanation),
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        }
    }
}

private const val MAX_NOTES = 3

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
            val action = stringResource(if (expanded) R.string.lab_chat_hide_changes else R.string.lab_chat_show_changes)
            Row(
                Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = action, onClick = onToggle)
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 6.dp)
                    .semantics { stateDescription = action },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = style, color = Linguize.colors.proof)
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "▴" else "▾", style = style, color = Linguize.colors.proof, modifier = Modifier.clearAndSetSemantics {})
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
 * The draft's check, over the composer: the fixes marked in the writer's own text, each with
 * its reason a tap away and a Skip; then Send with fixes or Send as written. Details opens
 * the full card (Meaning, assumptions, rewordings).
 */
@Composable
internal fun ReviewPanel(chat: ChatViewModel, check: CheckViewModel, maxHeight: Dp, onOpenSettings: () -> Unit) {
    val state = check.state
    val reviewed = (state as? CardState.Done)?.result as? CheckResult.Reviewed
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Linguize.colors.popup)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            chat.send is SendState.Checking -> {
                Text(chat.draft.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4)
                Spacer(Modifier.height(10.dp))
                Progress(stringResource(R.string.lab_chat_checking))
            }
            chat.details && state != null -> ResultCard(state, cardActions(chat, check, onOpenSettings), Modifier.fillMaxWidth(), scrollable = false)
            reviewed != null -> InlineFixes(chat, reviewed)
            state != null -> ResultCard(state, cardActions(chat, check, onOpenSettings), Modifier.fillMaxWidth(), scrollable = false)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (reviewed != null) {
                LinguizeButton(stringResource(if (chat.details) R.string.lab_chat_hide_details else R.string.lab_chat_details),
                    { chat.showDetails(!chat.details) }, ButtonKind.Text)
            }
            LinguizeButton(stringResource(R.string.lab_chat_keep_editing), chat::keepEditing, ButtonKind.Text)
            LinguizeButton(stringResource(R.string.lab_chat_send_as_written), chat::sendAsWritten, ButtonKind.Outlined)
            if (reviewed != null) {
                val kept = reviewed.revision.edits(EditKind.Fix).count { it.id !in chat.skipped }
                LinguizeButton(stringResource(if (kept > 0) R.string.lab_chat_send_with_fixes else R.string.lab_chat_send),
                    chat::sendWithFixes, ButtonKind.Filled)
            }
        }
    }
}

/** The fixes in the writer's own words: underlined where they go, listed with Skip. */
@Composable
private fun InlineFixes(chat: ChatViewModel, reviewed: CheckResult.Reviewed) {
    val revision = reviewed.revision
    val fixes = revision.edits(EditKind.Fix).sortedBy { it.start }
    val proof = Linguize.colors.proof
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (fixes.isEmpty()) {
        // Only possible when some fixes were withheld: say so, and offer the full card.
        Text(stringResource(R.string.lab_chat_fixes_withheld), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(pluralStringResource(R.plurals.lab_chat_fix_count, fixes.size, fixes.size), style = MaterialTheme.typography.labelLarge,
        color = proof, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Spacer(Modifier.height(6.dp))
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = buildAnnotatedString {
        append(revision.original)
        fixes.forEach { fix ->
            val end = if (fix.end > fix.start) fix.end else minOf(fix.start + 1, revision.original.length)
            val start = if (fix.end > fix.start) fix.start else maxOf(0, end - 1)
            if (end <= start) return@forEach
            val skippedFix = fix.id in chat.skipped
            addStyle(
                SpanStyle(
                    color = if (skippedFix) muted else proof,
                    textDecoration = if (skippedFix) TextDecoration.LineThrough else TextDecoration.Underline,
                    background = if (chat.focusedFix == fix.id) proof.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Unspecified,
                ),
                start, end,
            )
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layout = it },
        modifier = Modifier
            .semantics { contentDescription = revision.original }
            .pointerInput(fixes) {
                detectTapGestures { position ->
                    val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                    fixes.firstOrNull { offset in it.start..maxOf(it.start, it.end - 1) }?.let { chat.focusFix(it.id) }
                }
            },
    )
    fixes.forEach { fix -> FixRow(fix, skipped = fix.id in chat.skipped, open = chat.focusedFix == fix.id || fixes.size == 1,
        onOpen = { chat.focusFix(fix.id) }, onSkip = { chat.toggleSkip(fix.id) }) }
}

@Composable
private fun FixRow(fix: Edit, skipped: Boolean, open: Boolean, onOpen: () -> Unit, onSkip: () -> Unit) {
    val change = changeText(fix.from, fix.replacement)
    val showReason = stringResource(R.string.lab_chat_show_reason)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                change,
                style = MaterialTheme.typography.bodyMedium,
                color = if (skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (skipped) TextDecoration.LineThrough else null,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = showReason, onClick = onOpen)
                    .heightIn(min = 40.dp)
                    .padding(vertical = 8.dp),
            )
            LinguizeButton(stringResource(if (skipped) R.string.lab_chat_keep_fix else R.string.lab_chat_skip_fix), onSkip, ButtonKind.Text,
                modifier = Modifier.semantics { stateDescription = change })
        }
        if (open) fix.why?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The full card's actions: accept, undo and clarify update the same check that Send uses. */
@Composable
private fun cardActions(chat: ChatViewModel, check: CheckViewModel, onOpenSettings: () -> Unit): CardActions {
    val context = LocalContext.current
    return CardActions(
        onCopy = { text, kind -> context.copyToClipboard(text); check.copied(kind) },
        review = ReviewActions(
            onAccept = check::accept,
            onAcceptAll = { check.acceptAll(it) },
            onUndo = check::undo,
            onApply = chat::sendWithFixes,
            applyLabel = R.string.lab_chat_send,
        ),
        onRetry = check::recheck,
        onOpenSettings = onOpenSettings,
        onSettle = check::settle,
        onRemoveChoice = check::removeChoice,
        onClarify = check::clarifyMeaning,
        onRemoveClarification = check::removeMeaning,
        language = { check.pinnedLanguage },
    )
}
