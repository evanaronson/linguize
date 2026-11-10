package com.evanaronson.linguize.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanaronson.linguize.ui.theme.Linguize

/*
 * Pieces shared by the card and the app screens. Two visual rules hold everywhere:
 * an underline is a change you can accept, and a row with a chevron opens more detail
 * or a reading you can clarify.
 */

val InsetShape = RoundedCornerShape(12.dp)

/** Text set apart on a grey inset: a meaning, a translation, saved text. Selectable. */
@Composable
fun TextBlock(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier
                .fillMaxWidth()
                .clip(InsetShape)
                .background(Linguize.colors.panel)
                .padding(12.dp),
        )
    }
}

/** "›", or "‹" right to left. Decoration only: screen readers skip it. */
@Composable
fun Chevron(modifier: Modifier = Modifier, size: Int = 22) {
    Text(
        if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›",
        fontSize = size.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/** A full-width drill-down row: a reading the writer can clarify ("Clarify what you mean ›"). */
@Composable
fun ClarifyLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, onClickLabel: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
        Chevron()
    }
}

/**
 * A drill-down at the bottom of a card, edge to edge under a hairline: a title, a muted
 * subtitle and a chevron. The card's last item; place it outside the card's padding.
 */
@Composable
fun FooterRow(title: String, subtitle: String, onClick: () -> Unit, onClickLabel: String? = null) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = onClickLabel, onClick = onClick)
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Chevron()
        }
    }
}

/** Muted body text: a status line, an empty state, a caption. */
@Composable
fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier)
}
