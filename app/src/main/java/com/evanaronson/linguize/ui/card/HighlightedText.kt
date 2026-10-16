package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.SuggestionAssessment
import kotlinx.coroutines.delay

/** One change shown in the text: where it sits, what it replaced, and why. */
data class Highlight(
    val id: Int,
    val range: IntRange,
    val from: String,
    val why: String?,
    val assessment: SuggestionAssessment? = null,
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
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val (panels, visible) = remember(text, changes) { changes.partition { it.needsPanel(text) } }
    val selected = visible.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
    // Drawing runs every frame. Index neighboring edges once instead of rescanning
    // every highlight for every other highlight as a longer check is scrolled.
    val starts = remember(visible) { visible.mapTo(HashSet()) { it.range.first } }
    val ends = remember(visible) { visible.mapTo(HashSet()) { it.range.last + 1 } }

    val annotated = remember(text, changes) {
        buildAnnotatedString {
            append(text)
            for (change in visible) {
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), change.range.first, change.range.last + 1)
            }
            append(" ")
            appendInlineContent("copy", "\uFFFC")
        }
    }

    // Resolve labels during composition so screen-reader actions also follow locale changes.
    val actions = visible.flatMap { change ->
        val to = text.substring(change.range)
        val whyLabel = if (change.from.isEmpty()) stringResource(R.string.change_why_add, to)
            else stringResource(R.string.change_why_replace, change.from, to)
        val replaceLabel = if (change.from.isEmpty()) stringResource(R.string.change_add, to)
            else stringResource(R.string.change_replace, change.from, to)
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
                    val result = layout ?: return@drawBehind
                    // Highlights grow a little past their text, except where two changes touch,
                    // where both pull back so a gap keeps them visibly separate.
                    val grow = 2.5.dp.toPx()
                    val gap = 1.dp.toPx()
                    val padY = 1.dp.toPx()
                    val radius = CornerRadius(5.dp.toPx())
                    visible.forEachIndexed { index, change ->
                        val touchesBefore = change.range.first in ends
                        val touchesAfter = change.range.last + 1 in starts
                        val padLeft = if (touchesBefore) -gap else grow
                        val padRight = if (touchesAfter) -gap else grow
                        val color = accent.copy(alpha = if (index == selected) 0.42f else 0.18f)
                        for ((left, top, right, bottom) in lineBoxes(result, change.range)) {
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(left - padLeft, top + padY),
                                size = Size(right - left + padLeft + padRight, bottom - top - 2 * padY),
                                cornerRadius = radius,
                            )
                        }
                    }
                }
                .pointerInput(visible) {
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
                onReplace = onReplace?.let { replace -> { replace(change.id) } },
                context = change.context(text),
            )
        }

        val shown = selected?.let { visible.getOrNull(it) }
        // Whenever a highlight is tapped, scroll (the card, or the screen around it) so its
        // explanation and Replace button are fully visible, once the panel has opened.
        val explanation = remember { BringIntoViewRequester() }
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
                )
            }
        }
    }
}

@Composable
private fun Explanation(change: Highlight, replacement: String, accent: Color, onReplace: (() -> Unit)?, context: String? = null) {
    val from = change.from.visibleValue()
    val to = replacement.visibleValue()
    val description = when {
        change.from.isEmpty() -> stringResource(R.string.change_add, to)
        replacement.isEmpty() -> stringResource(R.string.change_remove, from)
        else -> stringResource(R.string.change_becomes, from, to)
    }
    val display = if (change.from.isEmpty() || replacement.isEmpty()) description
        else stringResource(R.string.change_replacement, from, to)
    // Read out as it opens, which matters when it was opened by a screen reader's "Why" action.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.12f),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
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
                change.why?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssessmentDetails(change.assessment)
            }
            if (onReplace != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onReplace) { Text(stringResource(R.string.accept)) }
            }
        }
    }
}

@Composable
private fun String.visibleValue(): String = when {
    isEmpty() -> ""
    !isSpacing() -> trim()
    any { it.isLineBreak() } -> stringResource(R.string.change_line_break)
    length == 1 && this[0] == ' ' -> stringResource(R.string.change_space)
    else -> stringResource(R.string.change_spacing)
}

private data class LineBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One box per line the range covers, so a highlight wraps cleanly. */
private fun lineBoxes(layout: TextLayoutResult, range: IntRange): List<LineBox> {
    if (range.isEmpty() || range.last >= layout.layoutInput.text.length) return emptyList()
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last)
    return (firstLine..lastLine).map { line ->
        val left = if (line == firstLine) layout.getBoundingBox(range.first).left else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getBoundingBox(range.last).right else layout.getLineRight(line)
        LineBox(left, layout.getLineTop(line), right, layout.getLineBottom(line))
    }
}

/**
 * The highlight closest to [position], if one is within [maxDistance]. A tap on
 * a highlight always picks it; otherwise a finger-sized miss still reaches a
 * narrow one such as a lone comma.
 */
private fun nearest(layout: TextLayoutResult, changes: List<Highlight>, position: Offset, maxDistance: Float): Highlight? =
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
