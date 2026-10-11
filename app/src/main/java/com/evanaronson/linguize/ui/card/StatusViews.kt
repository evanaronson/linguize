package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class Mark { Good, Unsure, Problem }

/** A large badge with a title and optional detail: the whole card's message. */
@Composable
internal fun Outcome(mark: Mark, title: String, detail: String? = null) {
    // Polite live region: a screen reader announces when a check finishes or fails.
    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        MarkBadge(mark, 36.dp)
        Spacer(Modifier.size(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small badge with one line: a section's message. */
@Composable
internal fun StatusLine(mark: Mark, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MarkBadge(mark, 22.dp)
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun MarkBadge(mark: Mark, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val (glyph, background, foreground) = when (mark) {
        Mark.Good -> Triple("✓", colors.primaryContainer, colors.onPrimaryContainer)
        Mark.Unsure -> Triple("?", colors.secondaryContainer, colors.onSecondaryContainer)
        Mark.Problem -> Triple("!", colors.errorContainer, colors.onErrorContainer)
    }
    // The glyph only repeats what the text beside it says, so screen readers skip it.
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(size).clearAndSetSemantics {}) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = foreground,
                style = if (size > 30.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            )
        }
    }
}
