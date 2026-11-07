package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Four button weights, plus a destructive text treatment. A card has at most one
 * [Filled] action at a time: the next step (Apply, Clarify, Open settings).
 * [Tonal] is a section's own action (Accept, Accept all). [Outlined] steps back from Filled
 * when another action takes over.
 * [Text] is everything else. [Destructive] uses that same weight in proof red.
 */
enum class ButtonKind { Filled, Tonal, Outlined, Text, Destructive }

/**
 * A 40dp pill in one of the [ButtonKind] weights. Disabled buttons keep their colours
 * at 38% opacity, so a disabled primary still reads as the primary it will become.
 */
@Composable
fun LinguizeButton(
    text: String,
    onClick: () -> Unit,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LinguizeButton(onClick, kind, modifier, enabled) { Text(text) }
}

@Composable
fun LinguizeButton(
    onClick: () -> Unit,
    kind: ButtonKind,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Material dims disabled buttons to grey; the design keeps the button and fades it.
    val faded = if (enabled) modifier else modifier.alpha(DISABLED_ALPHA)
    val sized = faded.heightIn(min = 40.dp)
    fun ButtonColors.kept() = copy(disabledContainerColor = containerColor, disabledContentColor = contentColor)
    when (kind) {
        ButtonKind.Filled -> Button(
            onClick, sized, enabled,
            colors = ButtonDefaults.buttonColors(colors.primary, colors.onPrimary).kept(),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            content = content,
        )
        ButtonKind.Tonal -> Button(
            onClick, sized, enabled,
            colors = ButtonDefaults.buttonColors(colors.secondaryContainer, colors.onSecondaryContainer).kept(),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            content = content,
        )
        ButtonKind.Outlined -> OutlinedButton(
            onClick, sized, enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface).kept(),
            border = BorderStroke(1.dp, colors.outline),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            content = content,
        )
        ButtonKind.Text, ButtonKind.Destructive -> TextButton(
            onClick, sized, enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (kind == ButtonKind.Destructive) colors.error else colors.onSurface,
            ).kept(),
            contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
            content = content,
        )
    }
}

const val DISABLED_ALPHA = 0.38f
