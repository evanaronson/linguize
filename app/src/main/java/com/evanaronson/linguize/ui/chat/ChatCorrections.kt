package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.chat.ChatChange
import com.evanaronson.linguize.core.chat.ChatLabels
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.ui.card.ChangeLine
import com.evanaronson.linguize.ui.card.ExplanationPanel
import com.evanaronson.linguize.ui.card.Highlight
import com.evanaronson.linguize.ui.card.LINE_BREAK_MARK
import com.evanaronson.linguize.ui.card.CARET_MARK
import com.evanaronson.linguize.ui.card.drawChangeMark
import com.evanaronson.linguize.ui.card.lineBoxes
import com.evanaronson.linguize.ui.card.nearest
import com.evanaronson.linguize.ui.card.selectedChangeTint
import com.evanaronson.linguize.ui.card.visibleValue
import com.evanaronson.linguize.ui.components.Disclosure
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.phrasebook.LocalPhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.PhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.ProvidePhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.phraseCandidate
import com.evanaronson.linguize.ui.teachme.LessonSource
import com.evanaronson.linguize.ui.teachme.LocalLessonSource
import com.evanaronson.linguize.ui.teachme.lessonTopic
import com.evanaronson.linguize.ui.teachme.sentenceAround
import com.evanaronson.linguize.ui.theme.Linguize

/** Marks the block a corrected bubble opens, for tests of its size. */
internal const val CHANGES_TAG = "lab_chat_changes"

/** Whether a tap on the writer's bubble opens anything: only a message sent with changes. */
internal fun hasCorrections(message: ChatMessage): Boolean =
    message.fromWriter && message.check == SentCheck.Corrected && message.changes.isNotEmpty()

/**
 * A message's changes in reading order: by place in what was typed, an insertion before a
 * replacement at the same place (the card's order within a section).
 */
internal fun readingOrder(changes: List<ChatChange>): List<ChatChange> =
    changes.sortedWith(compareBy({ it.start }, { it.end > it.start }, { it.end }))

/**
 * What was typed, with each change written in place, before then after: the removed words
 * (struck through, see [struck]) and then what replaced them. [ranges] holds each change's span
 * in [text], in [readingOrder]; null for a change that overlaps an earlier one, which then
 * shows only in the list. Spacing has nothing to see, so it shows as the card's marks: ¶ for a
 * line break, ‸ for other spacing.
 */
internal data class ChangedText(val text: String, val ranges: List<IntRange?>, val struck: List<IntRange>)

internal fun changedText(raw: String, ordered: List<ChatChange>): ChangedText {
    val out = StringBuilder()
    val ranges = mutableListOf<IntRange?>()
    val struck = mutableListOf<IntRange>()
    var pos = 0
    for (change in ordered) {
        if (change.start < pos || change.end > raw.length) {
            ranges += null
            continue
        }
        out.append(raw, pos, change.start)
        val start = out.length
        val from = shown(change.from)
        val to = shown(change.to)
        if (from.isNotEmpty()) {
            out.append(from)
            struck += start until out.length
        }
        // Two words side by side keep a space between them; punctuation sits close.
        if (from.isNotEmpty() && to.isNotEmpty() && from.last().isLetterOrDigit() && to.first().isLetterOrDigit()) out.append(' ')
        out.append(to)
        ranges += if (out.length > start) start until out.length else null
        pos = change.end
    }
    out.append(raw, pos, raw.length)
    return ChangedText(out.toString(), ranges, struck)
}

private fun shown(part: String): String = when {
    part.isEmpty() || part.any { !it.isWhitespace() } -> part
    part.any { it in "\r\n\u0085  " } -> LINE_BREAK_MARK
    else -> CARET_MARK
}

/**
 * The quiet line at the bottom end of the writer's bubble: how it was checked ("Looks good",
 * "Sent as written", "Corrected, 2 changes" or "Reworded, 2 changes"); null for no check.
 */
@Composable
internal fun checkStatus(message: ChatMessage): String? = when (message.check) {
    SentCheck.None -> null
    SentCheck.LooksGood -> stringResource(R.string.lab_chat_looks_good)
    SentCheck.AsWritten -> stringResource(R.string.lab_chat_as_written)
    SentCheck.Corrected -> {
        val count = message.changes.size
        // Lab, Chat tab IA: "Corrected" only when a fix was accepted; rewordings alone weren't mistakes.
        when (ChatLabels.sent(message.changes)) {
            ChatLabels.Sent.Corrected -> pluralStringResource(R.plurals.lab_chat_changes_count, count, count)
            ChatLabels.Sent.Reworded -> pluralStringResource(R.plurals.lab_ia_chat_reworded_count, count, count)
        }
    }
}

/**
 * Under a corrected bubble, opened by tapping it: one compact block with every change. On top,
 * what was typed with each change marked in place the way the card marks it (solid proof
 * underline for a fix, dotted for a rewording, removed words struck through), then one line
 * per change in reading order, "estas → estàs". A row, or its underline, opens the card's
 * explanation under that row (the reason, Teach me, the Phrasebook star), in the
 * conversation's [language]; one at a time, like the card's underlines.
 */
@Composable
internal fun ChatCorrections(message: ChatMessage, language: String?, modifier: Modifier = Modifier) {
    val ordered = remember(message.changes) { readingOrder(message.changes) }
    val marked = remember(message.raw, ordered) { changedText(message.raw, ordered) }
    var open by rememberSaveable(message.id) { mutableStateOf<Int?>(null) }
    val select: (Int?) -> Unit = { open = it }
    SectionCard(
        modifier.padding(top = 4.dp, bottom = 8.dp).semantics { testTag = CHANGES_TAG },
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
    ) {
        ChangedTextView(message.raw, ordered, marked, open, select)
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(LocalLessonSource provides LessonSource(language, message.raw)) {
            ProvidePhraseOrigin(PhraseOrigin(language = { language }, sessionId = { message.sessionId })) {
                ordered.forEachIndexed { index, change ->
                    key(index) {
                        RowDivider()
                        ChangeRow(message.raw, change, language, expanded = open == index,
                            onExpandedChange = { select(if (it) index else null) })
                    }
                }
            }
        }
    }
}

/** The typed text with its changes marked; a tap on one opens its row. */
@Composable
private fun ChangedTextView(raw: String, ordered: List<ChatChange>, marked: ChangedText, open: Int?, onSelect: (Int?) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val struckColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(marked, struckColor) {
        buildAnnotatedString {
            append(marked.text)
            for (range in marked.struck) {
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = struckColor), range.first, range.last + 1)
            }
        }
    }
    val highlights = remember(marked, ordered) {
        marked.ranges.mapIndexedNotNull { index, range -> range?.let { Highlight(index, it, ordered[index].from, ordered[index].why) } }
    }
    val boxes = remember(layout, highlights) { highlights.map { h -> layout?.let { lineBoxes(it, h.range) }.orEmpty() } }
    val fix = Linguize.colors.proof
    val rewording = Linguize.colors.rewording
    val tint = selectedChangeTint()
    val currentOpen by rememberUpdatedState(open)
    val currentSelect by rememberUpdatedState(onSelect)
    // Screen readers hear what was typed, and reach each change's reason as an action, as on the card.
    val spoken = stringResource(R.string.lab_chat_changes_you_wrote, raw)
    val actions = ordered.mapIndexed { index, change ->
        CustomAccessibilityAction(whyLabel(change)) { onSelect(index); true }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyLarge,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = spoken
                customActions = actions
            }
            .drawBehind {
                highlights.forEachIndexed { i, h ->
                    val dotted = ordered[h.id].kind == EditKind.Natural
                    drawChangeMark(boxes[i], if (dotted) rewording else fix, dotted, selected = h.id == open, tint = tint)
                }
            }
            .pointerInput(highlights) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val hit = nearest(result, highlights, position, maxDistance = 24.dp.toPx()) ?: return@detectTapGestures
                    currentSelect(if (hit.id == currentOpen) null else hit.id)
                }
            },
    )
}

/** One change, "estas → estàs"; it opens the card's explanation for it. */
@Composable
private fun ChangeRow(raw: String, change: ChatChange, language: String?, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    Disclosure(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        expandLabel = stringResource(R.string.history_show_explanation),
        collapseLabel = stringResource(R.string.history_hide_explanation),
        headerPadding = PaddingValues(vertical = 4.dp),
        header = { ChangeLine(changeFrom(change), changeTo(change), description(change)) },
    ) {
        val sentence = sentenceAround(raw, change.start, change.end)
        ExplanationPanel(
            from = "", to = "", description = null,
            why = change.why?.takeIf { it.isNotBlank() } ?: stringResource(R.string.history_no_explanation),
            assessment = null,
            assumptions = emptyList(),
            answers = emptyList(),
            accent = Linguize.colors.proof,
            modifier = Modifier.padding(bottom = 10.dp),
            lesson = lessonTopic(change.from, change.to, change.why, language, sentence),
            phrase = phraseCandidate(change.from, change.to, change.why, change.kind, LocalPhraseOrigin.current),
        )
    }
}

/** Both sides shown only for a replacement; an addition or removal is said in words. */
@Composable
private fun changeFrom(change: ChatChange) = if (change.from.isEmpty() || change.to.isEmpty()) "" else change.from.visibleValue()

@Composable
private fun changeTo(change: ChatChange) = if (change.from.isEmpty() || change.to.isEmpty()) "" else change.to.visibleValue()

@Composable
private fun description(change: ChatChange): String {
    val from = change.from.visibleValue()
    val to = change.to.visibleValue()
    return when {
        change.from.isEmpty() -> stringResource(R.string.change_add, to)
        change.to.isEmpty() -> stringResource(R.string.change_remove, from)
        else -> stringResource(R.string.change_becomes, from, to)
    }
}

@Composable
private fun whyLabel(change: ChatChange): String {
    val from = change.from.visibleValue()
    val to = change.to.visibleValue()
    return when {
        change.from.isEmpty() -> stringResource(R.string.change_why_add, to)
        change.to.isEmpty() -> stringResource(R.string.change_why_remove, from)
        else -> stringResource(R.string.change_why_replace, from, to)
    }
}
