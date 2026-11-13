package com.evanaronson.linguize.ui.card

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.evanaronson.linguize.ui.components.DisclosureMotion
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    val tint = selectedChangeTint()
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

    // Lab, Type to accept: while the writer types the open change here, the text around it and
    // the field share the room above the keyboard. A long text narrows to the lines around the
    // change (the rest stays in the text, for screen readers too), and the card or screen
    // scrolls so the change, its line and the field are in view together.
    val typing = LocalCardTyping.current
    // Lab, Type to accept: the change typed correctly resolves in place during its hold.
    val resolvingId = typing?.resolving
    val resolvingIndex = visible.indexOfFirst { it.id == resolvingId }.takeIf { it >= 0 }
    val settle = remember { Animatable(0f) }
    LaunchedEffect(resolvingIndex != null, resolvingId) {
        settle.snapTo(0f)
        if (resolvingIndex != null) settle.animateTo(1f, tween(SETTLE_MILLIS, easing = LinearEasing))
    }
    val open = selected?.let { visible[it] }
    val typingHere = typing?.active == true && open != null && selection?.typeToAccept == true &&
        onReplace != null && TypeToAccept.target(text.substring(changes[selected].range)) != null
    val window = remember(layout, open?.range) { layout?.let { l -> open?.let { typingWindow(l, it.range) } } }
    val lastWindow = remember { arrayOfNulls<ClosedFloatingPointRange<Float>>(1) }
    if (typingHere && window != null) lastWindow[0] = window
    val crop by animateFloatAsState(
        if (typingHere && window != null) 1f else 0f,
        tween(if (typingHere) DisclosureMotion.EXPAND_MILLIS else DisclosureMotion.COLLAPSE_MILLIS, easing = FastOutSlowInEasing),
        label = "typingWindow",
    )
    val fade = with(LocalDensity.current) { WINDOW_FADE.toPx() }
    val anchor = remember { BringIntoViewRequester() }
    val column = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val currentLayout by rememberUpdatedState(layout)
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(typingHere, open?.id) {
        if (!typingHere) return@LaunchedEffect
        // Once the panel has opened and the keyboard has settled (and again if it resizes).
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            frameDelay(EXPAND_MILLIS)
            val box = column[0]?.takeIf { it.isAttached } ?: return@collectLatest
            val field = typing.field?.takeIf { it.isAttached } ?: return@collectLatest
            val result = currentLayout ?: return@collectLatest
            val range = currentOpen?.range ?: return@collectLatest
            val bottom = box.localBoundingBoxOf(field, clipBounds = false).bottom
            val shift = (lastWindow[0]?.start ?: 0f) * crop
            val line = result.getLineForOffset(range.first)
            val changeTop = result.getLineTop(line) - shift
            val contextTop = (result.getLineTop((line - TYPING_CONTEXT_LINES).coerceAtLeast(0)) - shift).coerceAtLeast(0f)
            val width = box.size.width.toFloat()
            // The change with a line before it; failing that, at least the change and the field.
            anchor.bringIntoView(Rect(0f, contextTop, width, bottom))
            anchor.bringIntoView(Rect(0f, changeTop, width, bottom))
        }
    }

    Column(Modifier.bringIntoViewRequester(anchor).onGloballyPositioned { column[0] = it }) {
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
                // Lab, Type to accept: the lines around the open change, while typing it.
                .graphicsLayer {
                    clip = crop > 0f
                    compositingStrategy = if (crop > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    drawContent()
                    val shown = lastWindow[0]
                    val edge = fade * crop
                    if (shown == null || edge <= 0f) return@drawWithContent
                    // Faded where the text goes on above or below.
                    if (shown.start > 0f) drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, edge),
                        blendMode = BlendMode.DstIn,
                    )
                    if (shown.endInclusive < (layout?.size?.height ?: 0) - 1) drawRect(
                        Brush.verticalGradient(listOf(Color.Black, Color.Transparent), size.height - edge, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val shown = lastWindow[0]
                        if (shown == null || crop == 0f) {
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        } else {
                            val top = (shown.start * crop).roundToInt()
                            val bottom = (placeable.height + (shown.endInclusive - placeable.height) * crop).roundToInt()
                            layout(placeable.width, (bottom - top).coerceAtLeast(0)) { placeable.place(0, -top) }
                        }
                    }
                .semantics {
                    spokenText?.let { contentDescription = it }
                    customActions = actions +CustomAccessibilityAction(copyDescription) {
                        onCopy()
                        true
                    }
                }
                .drawBehind {
                    boxes.forEachIndexed { index, segments ->
                        if (index == resolvingIndex) {
                            // Typed correctly: the mark lets go and the new words glow briefly, then settle.
                            val (underline, glow) = settleStep(settle.value)
                            drawChangeMark(segments, accent.copy(alpha = accent.alpha * underline), dotted,
                                selected = true, tint = accent.copy(alpha = SETTLE_GLOW * glow))
                        } else {
                            drawChangeMark(segments, accent, dotted, selected = index == selected, tint = tint)
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
                // While typing, the text and the field are kept in view together instead (above).
                if (typing?.active != true) explanation.bringIntoView()
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
                    typeToAccept = selection?.takeIf { it.typeToAccept && onReplace != null }
                        ?.let { TypeToAccept.target(replacement) }?.let { TypeTarget(change.id, it) },
                )
            }
        }
    }
}

/** The faint tint behind the open change. */
@Composable
internal fun selectedChangeTint(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.1f else 0.06f)

/**
 * One change's mark under its text [segments]: the [tint] behind it when [selected], then its
 * underline, solid [accent] for a fix or [dotted] for a rewording. Also draws Chat's corrections.
 */
internal fun DrawScope.drawChangeMark(segments: List<LineBox>, accent: Color, dotted: Boolean, selected: Boolean, tint: Color) {
    val offset = 3.dp.toPx()
    val stroke = (if (dotted) 1.5.dp else 2.dp).toPx()
    val dots = if (dotted) PathEffect.dashPathEffect(floatArrayOf(0.01f, 2.5.dp.toPx())) else null
    val pad = 2.dp.toPx()
    for (box in segments) {
        if (selected) {
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
    onSettle: ((String, String) -> Unit)? = null,
    /** Lab: the sentence the change sits in, for its lesson. */
    sentence: String? = null,
    rewording: Boolean = false,
    /** Lab, Review flow: moves on without accepting. */
    onSkip: (() -> Unit)? = null,
    /** Lab, Review flow: "Change 2 of 5", spoken with the change. */
    position: String? = null,
    /** Lab, Type to accept: what to type in place of Accept; null keeps the button. */
    typeToAccept: TypeTarget? = null,
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
        onAccept = onReplace,
        onSettle = onSettle,
        context = context,
        onSkip = onSkip,
        position = position,
        typeToAccept = typeToAccept,
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

/**
 * Lab, Type to accept: how a correctly typed change resolves in the text, within the hold
 * before it is accepted: the underline fades over the expand time, the soft highlight rises
 * with it, stays, then fades over the expand time again, ending before the accept.
 */
internal const val SETTLE_MILLIS = 2 * DisclosureMotion.EXPAND_MILLIS + DisclosureMotion.COLLAPSE_MILLIS

private const val SETTLE_GLOW = 0.22f

/** The underline's and the highlight's strength at [progress] (0 to 1) of [SETTLE_MILLIS]. */
internal fun settleStep(progress: Float): Pair<Float, Float> {
    val rise = DisclosureMotion.EXPAND_MILLIS.toFloat() / SETTLE_MILLIS
    val underline = 1f - (progress / rise).coerceIn(0f, 1f)
    val glow = when {
        progress < rise -> progress / rise
        progress < 1f - rise -> 1f
        else -> ((1f - progress) / rise).coerceIn(0f, 1f)
    }
    return underline to glow
}

/** Lab, Type to accept: a text this long or shorter is shown whole while typing. */
internal const val TYPING_FULL_LINES = 4

/** Lab, Type to accept: lines kept above and below the change while typing in a longer text. */
internal const val TYPING_CONTEXT_LINES = 1

/** How far the narrowed text fades out where it goes on. */
private val WINDOW_FADE = 12.dp

/**
 * Lab, Type to accept: the part of a long text to show while the change at [range] is typed,
 * from the top of the line before it to the bottom of the line after it, in pixels of the
 * laid-out text; null when the text is short enough to show whole.
 */
internal fun typingWindow(layout: TextLayoutResult, range: IntRange): ClosedFloatingPointRange<Float>? {
    val lines = layout.lineCount
    val length = layout.layoutInput.text.length
    if (lines <= TYPING_FULL_LINES || length == 0) return null
    val first = layout.getLineForOffset(range.first.coerceIn(0, length - 1))
    val last = layout.getLineForOffset(maxOf(range.first, range.last).coerceIn(0, length - 1))
    val from = (first - TYPING_CONTEXT_LINES).coerceAtLeast(0)
    val to = (last + TYPING_CONTEXT_LINES).coerceAtMost(lines - 1)
    if (from == 0 && to == lines - 1) return null
    return layout.getLineTop(from)..layout.getLineBottom(to)
}
