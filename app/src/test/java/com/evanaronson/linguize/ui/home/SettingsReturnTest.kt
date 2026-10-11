package com.evanaronson.linguize.ui.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card that opened Settings is told once when Settings is left, and only that card. */
class SettingsReturnTest {
    private val told = mutableListOf<String>()
    private val first: () -> Unit = { told += "first" }
    private val second: () -> Unit = { told += "second" }
    private val home: () -> Unit = { told += "home" }

    @After
    fun forget() {
        SettingsReturn.cancel(first)
        SettingsReturn.cancel(second)
    }

    @Test
    fun theWaitingCardIsToldOnce() {
        SettingsReturn.left() // Nothing waits: nothing happens.
        SettingsReturn.await(first, home)
        SettingsReturn.left()
        SettingsReturn.left()
        assertEquals(listOf("first"), told)
    }

    @Test
    fun aLaterCardTakesTheWaitAndAClosedOneLeavesIt() {
        SettingsReturn.await(first, home)
        SettingsReturn.await(second, home)
        // Cancelling a card that no longer waits leaves the one that does.
        SettingsReturn.cancel(first)
        SettingsReturn.left()
        assertEquals(listOf("second"), told)

        SettingsReturn.await(first, home)
        SettingsReturn.cancel(first)
        SettingsReturn.left()
        assertEquals(listOf("second"), told)
    }

    @Test
    fun homeEndsTheWaitingCardWithoutRestoringItLater() {
        SettingsReturn.await(first, home)
        SettingsReturn.home()
        SettingsReturn.left()
        SettingsReturn.home()
        assertEquals(listOf("home"), told)
    }

    @Test
    fun homeOnlyEndsTheCurrentCardAndIgnoresCancelledCards() {
        SettingsReturn.await(first, { told += "first home" })
        SettingsReturn.await(second, { told += "second home" })
        SettingsReturn.cancel(first)
        SettingsReturn.home()
        assertEquals(listOf("second home"), told)

        SettingsReturn.await(first, home)
        SettingsReturn.cancel(first)
        SettingsReturn.home()
        assertEquals(listOf("second home"), told)
    }
}

