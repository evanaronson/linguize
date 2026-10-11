package com.evanaronson.linguize.data

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which selection-menu entries are on, kept as their aliases' enabled state, and which one was tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelectionMenuTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val menu = SelectionMenu(context)

    private fun alias(name: String) = ComponentName(context.packageName, "com.evanaronson.linguize.menu.$name")

    @Test
    fun onlyAutoDetectIsOnAsInstalled() {
        assertEquals(setOf(MenuEntry.Auto), menu.enabled())
    }

    @Test
    fun entriesSwitchOnAndOff() {
        menu.setEnabled(MenuEntry.Catalan, true)
        menu.setEnabled(MenuEntry.Castilian, true)
        menu.setEnabled(MenuEntry.Auto, false)
        assertEquals(setOf(MenuEntry.Catalan, MenuEntry.Castilian), menu.enabled())
        menu.setEnabled(MenuEntry.Castilian, false)
        assertEquals(setOf(MenuEntry.Catalan), SelectionMenu(context).enabled())
    }

    @Test
    fun theTappedEntryComesFromItsAlias() {
        assertEquals(MenuEntry.Castilian, menu.entryFor(alias("CastilianizeEntry")))
        assertEquals(MenuEntry.Catalan, menu.entryFor(alias("CatalanizeEntry")))
        assertEquals(MenuEntry.Auto, menu.entryFor(alias("LinguizeEntry")))
        // Anything else checks with the language detected.
        assertEquals(MenuEntry.Auto, menu.entryFor(alias("SomethingElse")))
        assertEquals(MenuEntry.Auto, menu.entryFor(null))
    }

    @Test
    fun everyEntrysLanguageIsOneChecksKnow() {
        assertTrue(MenuEntry.entries.mapNotNull { it.language }.all { it in Language.all })
    }
}
