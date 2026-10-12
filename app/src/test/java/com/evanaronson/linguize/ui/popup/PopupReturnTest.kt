package com.evanaronson.linguize.ui.popup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card that opened Settings is told once when Settings is left, and only that card. */
class PopupReturnTest {
    private val told = mutableListOf<String>()
    private val first: () -> Unit = { told += "first" }
    private val second: () -> Unit = { told += "second" }

    @After
    fun forget() {
        PopupReturn.cancel(first)
        PopupReturn.cancel(second)
    }

    @Test
    fun theWaitingCardIsToldOnce() {
        PopupReturn.left(0) // Nothing waits: nothing happens.
        val token = PopupReturn.await(first)
        PopupReturn.left(token)
        PopupReturn.left(token)
        assertEquals(listOf("first"), told)
    }

    @Test
    fun aLaterCardTakesTheWaitAndAClosedOneLeavesIt() {
        val old = PopupReturn.await(first)
        val current = PopupReturn.await(second)
        // Cancelling a card that no longer waits leaves the one that does.
        PopupReturn.cancel(first)
        PopupReturn.left(old)
        assertEquals(emptyList<String>(), told)
        PopupReturn.left(current)
        assertEquals(listOf("second"), told)

        val cancelled = PopupReturn.await(first)
        PopupReturn.cancel(first)
        PopupReturn.left(cancelled)
        assertEquals(listOf("second"), told)
    }

    @Test
    fun visitingHomeDoesNotConsumeThePopupReturn() {
        val token = PopupReturn.await(first)
        assertEquals(emptyList<String>(), told)
        PopupReturn.left(token)
        PopupReturn.left(token)
        assertEquals(listOf("first"), told)
    }
}
