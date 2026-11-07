package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/*
 * Words carry the state; there are no status badges. The one colour is kept for marks.
 */

/** A title with optional detail: the whole card's message. */
@Composable
internal fun Outcome(title: String, detail: String? = null) {
    // Polite live region: a screen reader announces when a check finishes or fails.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One line for a section: what it found, when there is nothing to show. */
@Composable
internal fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** A quieter line for a section that is done ("All accepted"). */
@Composable
internal fun DoneLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
