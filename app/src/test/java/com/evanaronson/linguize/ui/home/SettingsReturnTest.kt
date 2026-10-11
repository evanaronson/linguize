package com.evanaronson.linguize.ui.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card that opened Settings is told once when Settings is left, and only that card. */
class SettingsReturnTest {
    private val told = mutableListOf<String>()
    private val first: () -> Unit = { told += "first" }
    private val second: () -> Unit = { told += "second" }

    @After
    fun forget() {
        SettingsReturn.cancel(first)
        SettingsReturn.cancel(second)
    }

    @Test
    fun theWaitingCardIsToldOnce() {
        SettingsReturn.left(0) // Nothing waits: nothing happens.
        val token = SettingsReturn.await(first)
        SettingsReturn.left(token)
        SettingsReturn.left(token)
        assertEquals(listOf("first"), told)
    }

    @Test
    fun aLaterCardTakesTheWaitAndAClosedOneLeavesIt() {
        val old = SettingsReturn.await(first)
        val current = SettingsReturn.await(second)
        // Cancelling a card that no longer waits leaves the one that does.
        SettingsReturn.cancel(first)
        SettingsReturn.left(old)
        assertEquals(emptyList<String>(), told)
        SettingsReturn.left(current)
        assertEquals(listOf("second"), told)

        val cancelled = SettingsReturn.await(first)
        SettingsReturn.cancel(first)
        SettingsReturn.left(cancelled)
        assertEquals(listOf("second"), told)
    }

    @Test
    fun visitingHomeDoesNotConsumeThePopupReturn() {
        val token = SettingsReturn.await(first)
        assertEquals(emptyList<String>(), told)
        SettingsReturn.left(token)
        SettingsReturn.left(token)
        assertEquals(listOf("first"), told)
    }
}
