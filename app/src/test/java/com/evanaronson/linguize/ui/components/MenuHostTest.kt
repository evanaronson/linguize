package com.evanaronson.linguize.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuHostTest {
    @Test
    fun opensBelowWhenThereIsRoom() {
        assertEquals(IntOffset(24, 104), menuPosition(
            Rect(24f, 52f, 180f, 100f), IntSize(360, 640), IntSize(220, 200), 4,
        ))
    }

    @Test
    fun opensAboveABottomCardAndStaysInsideTheRightEdge() {
        assertEquals(IntOffset(140, 296), menuPosition(
            Rect(200f, 500f, 340f, 548f), IntSize(360, 640), IntSize(220, 200), 4,
        ))
    }

    @Test
    fun staysInsideASmallWindow() {
        assertEquals(IntOffset.Zero, menuPosition(
            Rect(8f, 40f, 100f, 88f), IntSize(180, 120), IntSize(180, 120), 4,
        ))
    }

    @Test
    fun backClosesOnlyTheOpenMenu() {
        val state = MenuState()
        assertFalse(state.dismiss())
        state.show(Any(), Rect.Zero) {}
        assertTrue(state.dismiss())
        assertFalse(state.dismiss())
    }
}
