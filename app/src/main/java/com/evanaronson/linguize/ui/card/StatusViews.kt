package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** A title with optional detail: the whole card's message. */
@Composable
internal fun Outcome(title: String, detail: String? = null, success: Boolean = false) {
    // Polite live region: a screen reader announces when a check finishes or fails.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (success) SuccessLine(title, style = MaterialTheme.typography.titleMedium)
        else Text(title, style = MaterialTheme.typography.titleMedium)
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

/** A checkmark accompanies successful judgments and accepted changes; the words carry the state. */
@Composable
internal fun SuccessLine(
    text: String,
    quiet: Boolean = false,
    style: TextStyle = if (quiet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
) {
    val color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null,
            modifier = Modifier.size(18.dp), tint = color)
        Text(text, style = style, color = color)
    }
}
