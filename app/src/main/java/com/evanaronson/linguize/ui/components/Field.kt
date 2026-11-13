package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.evanaronson.linguize.R
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * The one text field: a 12dp-rounded box with a hairline outline that turns into a 2dp ink
 * border while focused. [label] sits above the box, [supporting] below it. [large] is for
 * the writer's own text (16sp, as in Check your text and Clarify what you mean); otherwise
 * 14sp. The placeholder is upright and muted, never a floating label.
 */
@Composable
fun LinguizeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    large: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fieldModifier: Modifier = Modifier,
    /** The editable node's label; defaults to the visible label when there is one. */
    accessibleLabel: String? = label,
    /** Reads [supporting] out as it appears or changes, for a message that follows typing. */
    announceSupporting: Boolean = false,
    /** What screen readers hear for [supporting] (and the field's error), when a short visible message needs more words. */
    spokenSupporting: String? = supporting,
    /**
     * Lab, Type to accept: the field's success state, with what screen readers hear ("Correct").
     * The outline is ink and a check sits at the end, the app's way of saying something is right
     * (the one colour, proof, is kept for marks and errors). The caller ignores input meanwhile;
     * the field stays editable so the keyboard stays up.
     */
    success: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = (if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
        .copy(color = colors.onSurface)
    val shape = InsetShape
    val borderColor = when {
        success != null -> colors.onSurface
        isError -> colors.error
        focused -> colors.onSurface
        else -> colors.outline
    }
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .semantics {
                    // Keep the label with the edit action, including when a value is present.
                    // An explicit description in fieldModifier takes precedence.
                    accessibleLabel?.let { contentDescription = it }
                    if (isError) error(spokenSupporting ?: supporting.orEmpty())
                },
            textStyle = if (success != null) style.copy(fontWeight = FontWeight.SemiBold) else style,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.onSurface),
            decorationBox = { inner ->
                // Focus thickens the border inward, so the text never moves.
                val width = if (focused || isError || success != null) 2.dp else 1.dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .background(Linguize.colors.popup, shape)
                        .border(width, borderColor, shape)
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = style, color = colors.onSurfaceVariant)
                        }
                        inner()
                    }
                    if (success != null) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = success,
                            tint = colors.onSurface,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(20.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .then(if (announceSupporting) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                    .then(if (spokenSupporting != null && spokenSupporting != supporting)
                        Modifier.semantics { contentDescription = spokenSupporting } else Modifier),
            )
        }
    }
}
