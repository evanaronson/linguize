package com.evanaronson.linguize.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** A menu in the existing window, including accessibility windows without popup tokens. */
class MenuState {
    internal var owner by mutableStateOf<Any?>(null)
        private set
    internal var anchor by mutableStateOf(Rect.Zero)
        private set
    internal var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    internal fun show(owner: Any, anchor: Rect, content: @Composable () -> Unit) {
        this.owner = owner
        this.anchor = anchor
        this.content = content
    }

    internal fun move(owner: Any, anchor: Rect) {
        if (this.owner === owner) this.anchor = anchor
    }

    /** True when Back closed a menu rather than the underlying card. */
    fun dismiss(): Boolean {
        if (owner == null) return false
        owner = null
        content = null
        return true
    }
}

internal val LocalMenuState = staticCompositionLocalOf<MenuState> { error("MenuHost is required") }

@Composable
fun MenuHost(state: MenuState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMenuState provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            val menu = state.content
            if (menu != null) {
                // Activities use their dispatcher; the accessibility window calls dismiss itself.
                if (LocalOnBackPressedDispatcherOwner.current != null) {
                    BackHandler { state.dismiss() }
                }
                Box(Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { state.dismiss() },
                ))
                var bounds by remember { mutableStateOf(Rect.Zero) }
                Layout(
                    content = menu,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding()
                        .onGloballyPositioned { bounds = it.boundsInRoot() },
                ) { measurables, constraints ->
                    val anchor = state.anchor.translate(-bounds.left, -bounds.top)
                    val gap = 4.dp.roundToPx()
                    val below = (constraints.maxHeight - anchor.bottom.toInt() - gap).coerceAtLeast(0)
                    val above = (anchor.top.toInt() - gap).coerceAtLeast(0)
                    val placeable = measurables.single().measure(constraints.copy(
                        minWidth = 0, minHeight = 0,
                        maxHeight = maxOf(below, above).coerceAtMost(constraints.maxHeight),
                    ))
                    val position = menuPosition(anchor, IntSize(constraints.maxWidth, constraints.maxHeight),
                        IntSize(placeable.width, placeable.height), gap)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(position.x, position.y)
                    }
                }
            }
        }
    }
}

/** Below the trigger when it fits; above otherwise, always inside the safe drawing area. */
internal fun menuPosition(anchor: Rect, available: IntSize, menu: IntSize, gap: Int): IntOffset {
    val below = anchor.bottom.toInt() + gap
    val above = anchor.top.toInt() - gap - menu.height
    val y = if (below + menu.height <= available.height) below else above
    return IntOffset(
        anchor.left.toInt().coerceIn(0, (available.width - menu.width).coerceAtLeast(0)),
        y.coerceIn(0, (available.height - menu.height).coerceAtLeast(0)),
    )
}
