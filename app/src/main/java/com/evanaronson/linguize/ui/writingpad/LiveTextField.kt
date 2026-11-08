package com.evanaronson.linguize.ui.writingpad

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import com.evanaronson.linguize.ui.card.lineBoxes
import com.evanaronson.linguize.ui.card.visibleValue
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * The pad's text, editable, with each live suggestion underlined in place: a solid proof
 * line for a fix, a dotted grey one for a rewording (lower when it runs under a fix), and a
 * short mark where something would be inserted. Tapping an underline moves the cursor into
 * it, which the view model reads as asking to see that change. Screen readers get one
 * action per change instead.
 */
@Composable
internal fun LiveTextField(
    value: TextFieldValue,
    suggestions: List<LiveSuggestion>,
    focused: Set<Long>,
    onValueChange: (TextFieldValue) -> Unit,
    onShow: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val proof = Linguize.colors.proof
    val grey = Linguize.colors.rewording
    val ink = MaterialTheme.colorScheme.onSurface
    val tint = ink.copy(alpha = if (isSystemInDarkTheme()) 0.1f else 0.06f)
    val style = MaterialTheme.typography.bodyLarge.copy(color = ink)
    val label = stringResource(R.string.lab_writing_pad_text_label)
    val actions = suggestions.map { suggestion ->
        CustomAccessibilityAction(changeLabel(suggestion)) {
            onShow(suggestion.key)
            true
        }
    }
    val fixes = suggestions.filter { it.kind == EditKind.Fix }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                customActions = actions
            }
            .drawBehind {
                // A layout from before the last edit would put marks in the wrong places.
                val result = layout?.takeIf { it.layoutInput.text.text == value.text } ?: return@drawBehind
                val stroke = 2.dp.toPx()
                val dotted = 1.5.dp.toPx()
                val dots = PathEffect.dashPathEffect(floatArrayOf(0.01f, 2.5.dp.toPx()))
                val pad = 2.dp.toPx()
                for (suggestion in suggestions.sortedBy { it.kind == EditKind.Fix }) {
                    val fix = suggestion.kind == EditKind.Fix
                    val color = if (fix) proof else grey
                    val lower = !fix && fixes.any { it.start < suggestion.end && suggestion.start < it.end }
                    val offset = (if (lower) 6.5.dp else 3.dp).toPx()
                    val boxes = lineBoxes(result, suggestion.start until suggestion.end)
                    if (boxes.isEmpty()) {
                        // An insertion, or a change to spacing alone: a short mark at its place.
                        val at = suggestion.start.coerceIn(0, value.text.length)
                        val cursor = result.getCursorRect(at)
                        val y = result.getLineBaseline(result.getLineForOffset(at)) + offset
                        val half = 4.dp.toPx()
                        if (suggestion.key in focused) {
                            drawRoundRect(tint, Offset(cursor.left - half - pad, cursor.top),
                                Size(2 * (half + pad), cursor.bottom - cursor.top), CornerRadius(3.dp.toPx()))
                        }
                        drawLine(color, Offset(cursor.left - half, y), Offset(cursor.left + half, y),
                            if (fix) stroke else dotted, cap = StrokeCap.Round)
                        continue
                    }
                    for (box in boxes) {
                        if (suggestion.key in focused) {
                            drawRoundRect(tint, Offset(box.left - pad, box.top),
                                Size(box.right - box.left + 2 * pad, box.bottom - box.top), CornerRadius(3.dp.toPx()))
                        }
                        val y = box.baseline + offset
                        drawLine(color, Offset(box.left, y), Offset(box.right, y),
                            if (fix) stroke else dotted,
                            cap = if (fix) StrokeCap.Butt else StrokeCap.Round,
                            pathEffect = if (fix) null else dots)
                    }
                }
            },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(stringResource(R.string.lab_writing_pad_text_placeholder), style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

/** "Why cami becomes camí", in words, for a screen reader's action. */
@Composable
internal fun changeLabel(suggestion: LiveSuggestion): String {
    val from = suggestion.from.visibleValue()
    val to = suggestion.replacement.visibleValue()
    return when {
        suggestion.from.isEmpty() -> stringResource(R.string.change_why_add, to)
        suggestion.replacement.isEmpty() -> stringResource(R.string.change_why_remove, from)
        else -> stringResource(R.string.change_why_replace, from, to)
    }
}
