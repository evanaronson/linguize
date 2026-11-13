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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.data.PopupPosition
import com.evanaronson.linguize.ui.components.DisclosureMotion

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
    position: PopupPosition = PopupPosition.Bottom,
    header: @Composable RowScope.() -> Unit = {},
) {
    FloatingPopup(onDismiss, onOpenApp, position, header) { modifier ->
        ResultCard(state, actions, modifier)
    }
}

/** Shared window presentation; the content decides its job and actions. */
@Composable
fun FloatingPopup(
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    position: PopupPosition = PopupPosition.Bottom,
    header: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val appName = stringResource(R.string.app_name)
    // Over another app too, Back closes the keyboard (the card's text fields) before the card.
    com.evanaronson.linguize.ui.components.KeyboardBackHandler()
    val typing = rememberCardTyping()
    Box(Modifier.fillMaxSize().semantics { paneTitle = appName }) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
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
            contentAlignment = when (position) {
                PopupPosition.Bottom -> Alignment.BottomCenter
                PopupPosition.Middle -> Alignment.Center
                PopupPosition.Top -> Alignment.TopCenter
            },
        ) {
            // Long text scrolls inside the card; keep some of the app visible to tap away. While a
            // change is typed (Type to accept), the card takes the room above the keyboard.
            val share by animateFloatAsState(
                if (typing.active) 1f else 0.8f,
                tween(if (typing.active) DisclosureMotion.EXPAND_MILLIS else DisclosureMotion.COLLAPSE_MILLIS, easing = FastOutSlowInEasing),
                label = "cardShare",
            )
            val cardMaxHeight = maxHeight * share
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
                    WordmarkButton(onOpenApp)
                    header()
                }
                val cardModifier = Modifier.fillMaxWidth().heightIn(max = cardMaxHeight)
                CompositionLocalProvider(LocalCardTyping provides typing) { content(cardModifier) }
            }
        }
    }
}

/** The wordmark on a pill: opens the app without closing the check. */
@Composable
private fun WordmarkButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Image(
            painterResource(R.drawable.wordmark),
            contentDescription = stringResource(R.string.open_linguize),
            modifier = Modifier.height(22.dp),
        )
    }
}
