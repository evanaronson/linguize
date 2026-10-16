package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.Assumption
import kotlinx.coroutines.delay

/** One change shown in the text: where it sits, what it replaced, and why. */
data class Highlight(
    val id: Int,
    val range: IntRange,
    val from: String,
    val why: String?,
    val assessment: SuggestionAssessment? = null,
    val assumptions: List<Assumption> = emptyList(),
)

/** Deletions and spacing cannot offer a visible text target; show their controls separately. */
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
 * Suggested text with a separate rounded highlight behind each change, so
 * adjacent changes read as distinct blocks. Tapping a highlight shows why,
 * with a Replace button to accept just that change when [onReplace] is set.
 * The same highlighted words are keyboard links that open the existing explanation.
 * Screen readers, which can't aim a tap at a highlight, get the same as actions
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
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val explanation = remember { BringIntoViewRequester() }
    val (panels, visible) = remember(text, changes) { changes.partition { it.needsPanel(text) } }
    val selected = visible.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
    val boxes = remember(layout, visible) {
        visible.map { change -> layout?.let { lineBoxes(it, change.range) }.orEmpty() }
    }
    // Neighboring changes need a gap on the edges that actually touch on screen.
    // Logical before/after can be on either side of an RTL or mixed-direction run.
    val leftEdges = remember(boxes) { boxes.flatten().filter { it.right > it.left }.mapTo(HashSet()) { it.top to it.left } }
    val rightEdges = remember(boxes) { boxes.flatten().filter { it.right > it.left }.mapTo(HashSet()) { it.top to it.right } }

    val annotated = remember(text, changes, accent) {
        buildAnnotatedString {
            append(text)
            for (change in visible) {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), change.range.first, change.range.last + 1)
                addLink(
                    LinkAnnotation.Clickable(
                        tag = change.id.toString(),
                        styles = TextLinkStyles(focusedStyle = SpanStyle(
                            background = accent.copy(alpha = 0.3f),
                            textDecoration = TextDecoration.Underline,
                        )),
                    ) { selectedId = if (selectedId == change.id) null else change.id },
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
        val whyLabel = when {
            change.from.isEmpty() -> stringResource(R.string.change_why_add, to)
            change.range.isEmpty() -> stringResource(R.string.change_why_remove, from)
            else -> stringResource(R.string.change_why_replace, from, to)
        }
        val replaceLabel = when {
            change.from.isEmpty() -> stringResource(R.string.change_add, to)
            change.range.isEmpty() -> stringResource(R.string.change_remove, from)
            else -> stringResource(R.string.change_replace, from, to)
        }
        listOfNotNull(
            CustomAccessibilityAction(whyLabel) {
                selectedId = change.id
                true
            },
            onReplace?.let { replace ->
                CustomAccessibilityAction(replaceLabel) {
                    selectedId = null
                    replace(change.id)
                    true
                }
            },
        )
    }

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
                    customActions = actions + CustomAccessibilityAction(copyDescription) {
                        onCopy()
                        true
                    }
                }
                .drawBehind {
                    // Highlights grow a little past their text, except where two changes touch,
                    // where both pull back so a gap keeps them visibly separate.
                    val grow = 2.5.dp.toPx()
                    val gap = 1.dp.toPx()
                    val padY = 1.dp.toPx()
                    val radius = CornerRadius(5.dp.toPx())
                    boxes.forEachIndexed { index, segments ->
                        val color = accent.copy(alpha = if (index == selected) 0.42f else 0.18f)
                        for ((left, top, right, bottom) in segments) {
                            val padLeft = if (top to left in rightEdges) -gap else grow
                            val padRight = if (top to right in leftEdges) -gap else grow
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(left - padLeft, top + padY),
                                size = Size((right - left + padLeft + padRight).coerceAtLeast(0f), bottom - top - 2 * padY),
                                cornerRadius = radius,
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
                        selectedId = if (hit == null || hit.id == selectedId) null else hit.id
                    }
                },
        )

        panels.forEach { change ->
            Spacer(Modifier.height(8.dp))
            Explanation(change, text.substring(change.range), accent,
                onReplace = onReplace?.let { replace -> { selectedId = null; replace(change.id) } },
                context = change.context(text),
                expanded = selectedId == change.id,
                onToggle = { selectedId = if (selectedId == change.id) null else change.id },
                modifier = if (selectedId == change.id) Modifier.bringIntoViewRequester(explanation) else Modifier,
                onSettle = onSettle,
            )
        }

        val shown = selected?.let { visible.getOrNull(it) }
        // Whenever a highlight is tapped, scroll (the card, or the screen around it) so its
        // explanation and Replace button are fully visible, once the panel has opened.
        LaunchedEffect(selectedId) {
            if (selectedId != null) {
                delay(EXPAND_MILLIS)
                explanation.bringIntoView()
            }
        }
        // Keeps showing the last explanation while it animates away. Its replacement is kept
        // with it: the change can leave because another one was accepted (a rewording retires
        // the fixes it touches), and then its range no longer fits the shorter text.
        val lastShown = remember { arrayOfNulls<Pair<Highlight, String>>(1) }
        if (shown != null) lastShown[0] = shown to text.substring(shown.range)
        AnimatedVisibility(visible = shown != null) {
            val (change, replacement) = lastShown[0] ?: return@AnimatedVisibility
            Column(Modifier.bringIntoViewRequester(explanation)) {
                Spacer(Modifier.height(8.dp))
                Explanation(
                    change,
                    replacement,
                    accent,
                    onReplace = onReplace?.let { replace -> { selectedId = null; replace(change.id) } },
                    onSettle = onSettle,
                )
            }
        }
    }
}

@Composable
private fun Explanation(
    change: Highlight,
    replacement: String,
    accent: Color,
    onReplace: (() -> Unit)?,
    context: String? = null,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onSettle: ((String, String) -> Unit)? = null,
) {
    val from = change.from.visibleValue()
    val to = replacement.visibleValue()
    val description = when {
        change.from.isEmpty() -> stringResource(R.string.change_add, to)
        replacement.isEmpty() -> stringResource(R.string.change_remove, from)
        else -> stringResource(R.string.change_becomes, from, to)
    }
    val display = if (change.from.isEmpty() || replacement.isEmpty()) description
        else stringResource(R.string.change_replacement, from, to)
    val whyLabel = when {
        change.from.isEmpty() -> stringResource(R.string.change_why_add, to)
        replacement.isEmpty() -> stringResource(R.string.change_why_remove, from)
        else -> stringResource(R.string.change_why_replace, from, to)
    }
    // Read out as it opens, which matters when it was opened by a screen reader's "Why" action.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.12f),
        modifier = modifier
            .then(if (onToggle != null) Modifier.clickable(onClickLabel = whyLabel, onClick = onToggle) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        buildAnnotatedString {
                            append(display)
                            if (from.isNotEmpty()) {
                                val fromStart = display.indexOf(from)
                                if (fromStart >= 0) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough),
                                    fromStart, fromStart + from.length)
                            }
                            val replacementStart = display.lastIndexOf(to)
                            if (to.isNotEmpty() && replacementStart >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold),
                                replacementStart, replacementStart + to.length)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        // In words: the arrow would be read out as "right arrow".
                        modifier = Modifier.semantics {
                            contentDescription = description
                        },
                    )
                    if (!context.isNullOrBlank()) {
                        Text(context, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (expanded) {
                        change.why?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AssessmentDetails(change.assessment)
                    }
                }
                if (onReplace != null) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = onReplace) { Text(stringResource(R.string.accept)) }
                }
            }
            if (expanded && change.assumptions.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.depends_on), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    change.assumptions.forEach { AssumptionItem(it, onSettle) }
                }
            }
        }
    }
}

@Composable
internal fun String.visibleValue(): String = when {
    isEmpty() -> ""
    !isSpacing() -> trim()
    any { it.isLineBreak() } -> stringResource(R.string.change_line_break)
    length == 1 && this[0] == ' ' -> stringResource(R.string.change_space)
    else -> stringResource(R.string.change_spacing)
}

internal data class LineBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

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
                    segments += LineBox(glyph.left, layout.getLineTop(line), glyph.right, layout.getLineBottom(line))
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
