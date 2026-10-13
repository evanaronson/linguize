package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R

/**
 * The card floating over a dimmed view of the app the text came from. Tapping
 * outside the card calls [onDismiss]. [header] sits just above the card.
 *
 * The dimmed backdrop that closes the card sits behind it rather than around it: a
 * clickable parent would merge everything in the card into one "Close" node for screen
 * readers, taking the text's per-change actions with it.
 */
@Composable
fun FloatingCard(
    state: CardState,
    actions: CardActions,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    header: @Composable RowScope.() -> Unit = {},
) {
    val appName = stringResource(R.string.app_name)
    Box(Modifier.fillMaxSize().semantics { paneTitle = appName }) {
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.close_card),
                    onClick = onDismiss,
                ),
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Long text scrolls inside the card; keep some of the app visible to tap away.
            val cardMaxHeight = maxHeight * 0.8f
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    // Taps on the card or header, even between its buttons, shouldn't reach the backdrop.
                    .pointerInput(Unit) { detectTapGestures {} },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FilledTonalButton(
                        onClick = onOpenApp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Image(
                            painterResource(R.drawable.wordmark),
                            contentDescription = stringResource(R.string.open_linguize),
                            modifier = Modifier.height(24.dp),
                        )
                    }
                    header()
                }
                val cardModifier = Modifier.fillMaxWidth().heightIn(max = cardMaxHeight)
                ResultCard(state, actions, cardModifier)
            }
        }
    }
}
