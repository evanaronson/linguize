package com.evanaronson.linguize.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.Loading
import com.evanaronson.linguize.ui.card.Outcome
import com.evanaronson.linguize.ui.card.ResultSurface
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton

/** Shown for the moment between opening and the window getting focus. */
@Composable
internal fun ReadingClipboardCard(modifier: Modifier) {
    ResultSurface(modifier) {
        Loading("", stringResource(R.string.reading_copied_text))
    }
}

/** The tile doesn't know whose words were copied, so the writer says: their own, or a message. */
@Composable
internal fun ChooseClipboardJobCard(
    text: String,
    onCheck: () -> Unit,
    onUnderstand: () -> Unit,
    modifier: Modifier,
) {
    ResultSurface(modifier) {
        Text(stringResource(R.string.copied_text), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.lab_quick_tile_choose_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinguizeButton(stringResource(R.string.lab_quick_tile_check_mine), onCheck, ButtonKind.Filled)
            LinguizeButton(stringResource(R.string.lab_quick_tile_understand_it), onUnderstand, ButtonKind.Tonal)
        }
    }
}

/** Why there's nothing to check, and a retry for after copying something. */
@Composable
internal fun ClipboardMessageCard(copied: Copied, onRetry: () -> Unit, modifier: Modifier) {
    val (title, hint) = when (copied) {
        Copied.NotText -> R.string.lab_quick_tile_not_text to R.string.lab_quick_tile_not_text_hint
        Copied.Unavailable -> R.string.lab_quick_tile_unavailable to R.string.lab_quick_tile_unavailable_hint
        else -> R.string.lab_quick_tile_empty to R.string.lab_quick_tile_empty_hint
    }
    ResultSurface(modifier) {
        Outcome(stringResource(title), stringResource(hint))
        Spacer(Modifier.height(12.dp))
        LinguizeButton(stringResource(R.string.retry), onRetry, ButtonKind.Filled)
    }
}
