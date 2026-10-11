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
        SettingsReturn.left() // Nothing waits: nothing happens.
        SettingsReturn.await(first)
        SettingsReturn.left()
        SettingsReturn.left()
        assertEquals(listOf("first"), told)
    }

    @Test
    fun aLaterCardTakesTheWaitAndAClosedOneLeavesIt() {
        SettingsReturn.await(first)
        SettingsReturn.await(second)
        // Cancelling a card that no longer waits leaves the one that does.
        SettingsReturn.cancel(first)
        SettingsReturn.left()
        assertEquals(listOf("second"), told)

        SettingsReturn.await(first)
        SettingsReturn.cancel(first)
        SettingsReturn.left()
        assertEquals(listOf("second"), told)
    }
}
