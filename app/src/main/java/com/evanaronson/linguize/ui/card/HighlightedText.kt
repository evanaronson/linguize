package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.teachme.LocalLessonSource
import com.evanaronson.linguize.ui.teachme.lessonTopic
import com.evanaronson.linguize.ui.teachme.sentenceAround
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.ui.phrasebook.LocalPhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.phraseCandidate
import kotlinx.coroutines.delay

/** One change shown in the text: where it sits, what it replaced, and why. */
data class Highlight(
    val id: Int,
    val range: IntRange,
    val from: String,
    val why: String?,
    val assessment: SuggestionAssessment? = null,
    /** Unanswered readings the change rests on; each can be clarified from the explanation. */
    val assumptions: List<Assumption> = emptyList(),
    /** The writer's answers to readings the change rests on. */
    val answers: List<String> = emptyList(),
)

/** Deletions and spacing have no visible text to underline; they get a mark (see [markChanges]). */
internal fun Highlight.needsPanel(text: String) = text.substring(range).isSpacing()

private fun Char.isLineBreak() = this in "\r\n\u0085\u2028\u2029"
private fun String.isSpacing() = all { it.isWhitespace() || it.isLineBreak() }

/** Nearby words locate a spacing/deletion control without changing its actual replacement span. */
internal fun Highlight.context(text: String): String {
    val before = Regex("""\S+\s*$""").find(text.substring(0, range.first))?.value.orEmpty()
    val after = Regex("""^\s*\S+""").find(text.substring(range.last + 1))?.value.orEmpty()
    return before + text.substring(range) + after
}

/**
 * Suggested text with each change underlined: a solid [accent] line for a fix, a dotted
 * one ([dotted]) for a rewording. An underline is always something to accept. Tapping
 * one shows why, in an [ExplanationPanel] under the text, with Accept when [onReplace]
 * is set; the tapped change gets a faint tint, not a heavier line.
 * The same changes are keyboard links that open the existing explanation.
 * Screen readers, which can't aim a tap at a change, get the same as actions
 * on the text: "Why" for each change, and "Replace" when [onReplace] is set.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightedText(
    text: String,
    changes: List<Highlight>,
    accent: Color,
    onReplace: ((Int) -> Unit)?,
    onCopy: () -> Unit,
    copyDescription: String,
    onSettle: ((String, String) -> Unit)? = null,
    dotted: Boolean = false,
    /** The explanation's edge; the fix colour even when the mark itself is grey. */
    edge: Color = accent,
    /** Lab, Review flow: a selection shared with the card's other sections; null keeps its own. */
    selection: ReviewSelection? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var ownSelectedId by remember { mutableStateOf<Int?>(null) }
    val selectedId = if (selection != null) selection.selected else ownSelectedId
    val select: (Int?) -> Unit = selection?.onSelect ?: { ownSelectedId = it }
    // Accepting from a shared selection lets the flow move on; on its own the text just closes it.
    val afterAccept: () -> Unit = if (selection != null) ({}) else ({ ownSelectedId = null })
    val explanation = remember { BringIntoViewRequester() }
    // Every change is drawn in the text: deletions and spacing as a mark at their place.
    val marked = remember(text, changes) { markChanges(text, changes) }
    val visible = remember(marked, changes) { changes.map { it.copy(range = marked.ranges.getValue(it.id)) } }
    val selected = visible.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
    val boxes = remember(layout, visible) {
        visible.map { change -> layout?.let { lineBoxes(it, change.range) }.orEmpty() }
    }
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.1f else 0.06f)
    val struckColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Links keep their identity (and keyboard focus) as the selection moves.
    val currentSelected by rememberUpdatedState(selectedId)
    val currentSelect by rememberUpdatedState(select)
    val annotated = remember(marked, visible, accent, struckColor) {
        buildAnnotatedString {
            append(marked.text)
            for (range in marked.struck) {
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = struckColor),
                    range.first, range.last + 1)
            }
            for (change in visible) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = change.id.toString(),
                        styles = TextLinkStyles(focusedStyle = SpanStyle(
                            background = accent.copy(alpha = 0.3f),
                            textDecoration = TextDecoration.Underline,
                        )),
                    ) { currentSelect(if (currentSelected == change.id) null else change.id) },
                    change.range.first,
                    change.range.last + 1,
                )
            }
            append(" ")
            appendInlineContent("copy", "\uFFFC")
        }
    }

    // Resolve labels during composition so screen-reader actions also follow locale changes.
    val actions = changes.flatMap { change ->
        val to = text.substring(change.range).visibleValue()
        val from = change.from.visibleValue()
        val whyLabel = whyLabel(change.from, text.substring(change.range), from, to)
        val replaceLabel = when {
            change.from.isEmpty() -> stringResource(R.string.change_add, to)
            change.range.isEmpty() -> stringResource(R.string.change_remove, from)
            else -> stringResource(R.string.change_replace, from, to)
        }
        listOfNotNull(
            CustomAccessibilityAction(whyLabel) {
                select(change.id)
                true
            },
            onReplace?.let { replace ->
                CustomAccessibilityAction(replaceLabel) {
                    afterAccept()
                    replace(change.id)
                    true
                }
            },
        )
    } + listOfNotNull(selection?.onSkip?.takeIf { onReplace != null && selected != null }?.let { skip ->
        CustomAccessibilityAction(stringResource(R.string.lab_review_flow_skip_action)) {
            skip()
            true
        }
    })
    // Screen readers hear the real text: the marks only stand in for what a tap can't reach.
    val spokenText = text.takeIf { marked.hasMarks }

    Column {
        Text(
            annotated,
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layout = it },
            inlineContent = mapOf("copy" to InlineTextContent(
                Placeholder(width = 2.em, height = 1.5.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = copyDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }),
            modifier = Modifier
                .semantics {
                    spokenText?.let { contentDescription = it }
                    customActions = actions +CustomAccessibilityAction(copyDescription) {
                        onCopy()
                        true
                    }
                }
                .drawBehind {
                    val offset = 3.dp.toPx()
                    val stroke = (if (dotted) 1.5.dp else 2.dp).toPx()
                    val dots = if (dotted) PathEffect.dashPathEffect(floatArrayOf(0.01f, 2.5.dp.toPx())) else null
                    val pad = 2.dp.toPx()
                    boxes.forEachIndexed { index, segments ->
                        for (box in segments) {
                            if (index == selected) {
                                drawRoundRect(
                                    color = tint,
                                    topLeft = Offset(box.left - pad, box.top),
                                    size = Size(box.right - box.left + 2 * pad, box.bottom - box.top),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                            }
                            val y = box.baseline + offset
                            drawLine(
                                accent, Offset(box.left, y), Offset(box.right, y), stroke,
                                cap = if (dotted) StrokeCap.Round else StrokeCap.Butt, pathEffect = dots,
                            )
                        }
                    }
                }
                .pointerInput(visible) {
                    // Link children own direct taps. This recognizer waits for an unconsumed
                    // down, preserving the nearest-highlight allowance for taps outside them.
                    detectTapGestures { position ->
                        val result = layout ?: return@detectTapGestures
                        val hit = nearest(result, visible, position, maxDistance = TOUCH_RADIUS.toPx())
                        currentSelect(if (hit == null || hit.id == currentSelected) null else hit.id)
                    }
                },
        )

        // Explanations use the real text and its ranges, never the marked copy.
        val shown = selected?.let { changes.getOrNull(it) }
        // Whenever a change is selected here (tapped, or reached by the review flow), scroll
        // (the card, or the screen around it) so its explanation and Accept button are fully
        // visible, once the panel has opened. Another section's selection leaves this one be,
        // and so does the one a result opens on: the card shows its own top first.
        val arrived = remember { BooleanArray(1) }
        LaunchedEffect(shown?.id) {
            val first = !arrived[0]
            arrived[0] = true
            if (shown != null && !(first && selection != null)) {
                delay(EXPAND_MILLIS)
                explanation.bringIntoView()
            }
        }
        // Keeps showing the last explanation while it animates away. Its replacement is kept
        // with it: the change can leave because another one was accepted (a rewording retires
        // the fixes it touches), and then its range no longer fits the shorter text.
        val lastShown = remember { arrayOfNulls<Pair<Highlight, String>>(1) }
        val lastSentence = remember { arrayOfNulls<String>(1) }
        val lastContext = remember { arrayOfNulls<String>(1) }
        if (shown != null) {
            lastShown[0] = shown to text.substring(shown.range)
            lastSentence[0] = sentenceAround(text, shown.range.first, shown.range.last + 1)
            // A deletion or spacing change also shows the words around it.
            lastContext[0] = if (shown.needsPanel(text)) shown.context(text) else null
        }
        AnimatedVisibility(visible = shown != null) {
            val (change, replacement) = lastShown[0] ?: return@AnimatedVisibility
            Column(Modifier.bringIntoViewRequester(explanation)) {
                Spacer(Modifier.height(8.dp))
                ChangeExplanation(
                    change,
                    replacement,
                    edge,
                    onReplace = onReplace?.let { replace -> { afterAccept(); replace(change.id) } },
                    context = lastContext[0],
                    onSettle = onSettle,
                    sentence = lastSentence[0],
                    rewording = dotted,
                    onSkip = selection?.onSkip?.takeIf { onReplace != null },
                    position = selection?.position?.takeIf { selection.selected == change.id },
                )
            }
        }
    }
}

@Composable
private fun whyLabel(rawFrom: String, replacement: String, from: String, to: String) = when {
    rawFrom.isEmpty() -> stringResource(R.string.change_why_add, to)
    replacement.isEmpty() -> stringResource(R.string.change_why_remove, from)
    else -> stringResource(R.string.change_why_replace, from, to)
}

@Composable
private fun ChangeExplanation(
    change: Highlight,
    replacement: String,
    edge: Color,
    onReplace: (() -> Unit)?,
    context: String? = null,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onSettle: ((String, String) -> Unit)? = null,
    /** Lab: the sentence the change sits in, for its lesson. */
    sentence: String? = null,
    rewording: Boolean = false,
    /** Lab, Review flow: moves on without accepting. */
    onSkip: (() -> Unit)? = null,
    /** Lab, Review flow: "Change 2 of 5", spoken with the change. */
    position: String? = null,
) {
    val from = change.from.visibleValue()
    val to = replacement.visibleValue()
    val description = when {
        change.from.isEmpty() -> stringResource(R.string.change_add, to)
        replacement.isEmpty() -> stringResource(R.string.change_remove, from)
        else -> stringResource(R.string.change_becomes, from, to)
    }
    ExplanationPanel(
        from = if (change.from.isEmpty() || replacement.isEmpty()) "" else from,
        to = if (change.from.isEmpty() || replacement.isEmpty()) "" else to,
        description = description,
        why = change.why,
        assessment = change.assessment,
        assumptions = change.assumptions,
        answers = change.answers,
        accent = edge,
        modifier = modifier,
        onAccept = onReplace,
        onSettle = onSettle,
        context = context,
        expanded = expanded,
        onToggle = onToggle,
        onToggleLabel = whyLabel(change.from, replacement, from, to),
        onSkip = onSkip,
        position = position,
        lesson = lessonTopic(change.from, replacement, change.why, LocalLessonSource.current?.language, sentence),
        phrase = phraseCandidate(change.from, replacement, change.why,
            if (rewording) EditKind.Natural else EditKind.Fix, LocalPhraseOrigin.current),
    )
}

@Composable
internal fun String.visibleValue(): String = when {
    isEmpty() -> ""
    !isSpacing() -> trim()
    any { it.isLineBreak() } -> stringResource(R.string.change_line_break)
    length == 1 && this[0] == ' ' -> stringResource(R.string.change_space)
    else -> stringResource(R.string.change_spacing)
}

internal data class LineBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val baseline: Float = bottom)

/** Selected visual segments, which can be disconnected within one bidi line. */
internal fun lineBoxes(layout: TextLayoutResult, range: IntRange): List<LineBox> {
    if (range.isEmpty() || range.first < 0 || range.last >= layout.layoutInput.text.length) return emptyList()
    return range
        .filterNot { layout.layoutInput.text[it].isLineBreak() }
        .groupBy { layout.getLineForOffset(it) }
        .flatMap { (line, offsets) ->
            val segments = mutableListOf<LineBox>()
            // Cluster continuation offsets can have zero advance. Retain their position
            // so even a range containing only a combining mark keeps its touch allowance.
            val glyphs = offsets.mapNotNull { offset ->
                val glyph = layout.getBoundingBox(offset)
                // Wrapped trailing spaces can sit at a detached line edge with no ink.
                glyph.takeUnless { it.width == 0f && layout.layoutInput.text[offset].isWhitespace() }
            }.sortedBy { it.left }
            for (glyph in glyphs) {
                val previous = segments.lastOrNull()
                if (previous != null && glyph.left <= previous.right) {
                    segments[segments.lastIndex] = previous.copy(right = maxOf(previous.right, glyph.right))
                } else {
                    segments += LineBox(glyph.left, layout.getLineTop(line), glyph.right, layout.getLineBottom(line), layout.getLineBaseline(line))
                }
            }
            segments
        }
}

/**
 * The highlight closest to [position], if one is within [maxDistance]. A tap on
 * a highlight always picks it; otherwise a finger-sized miss still reaches a
 * narrow one such as a lone comma.
 */
internal fun nearest(layout: TextLayoutResult, changes: List<Highlight>, position: Offset, maxDistance: Float): Highlight? =
    changes
        .map { change -> change to lineBoxes(layout, change.range).minOfOrNull { distance(it, position) } }
        .filter { (_, distance) -> distance != null && distance <= maxDistance }
        .minByOrNull { (_, distance) -> distance!! }
        ?.first

/** Distance from [point] to [box]; zero inside it. */
private fun distance(box: LineBox, point: Offset): Float {
    val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
    val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/** How far from a highlight a tap still selects it: roughly a fingertip. */
private val TOUCH_RADIUS = 24.dp

/** Long enough for the explanation panel's expand animation to finish. */
private const val EXPAND_MILLIS = 320L
