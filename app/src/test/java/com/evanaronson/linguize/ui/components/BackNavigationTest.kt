package com.evanaronson.linguize.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lab, Consistency: with the keyboard up, the first Back closes it; only the next one navigates. */
class BackNavigationTest {
    @Test fun theKeyboardClosesFirst() {
        assertEquals(BackAction.CloseKeyboard, backAction(imeVisible = true, handles = true))
        assertEquals(BackAction.CloseKeyboard, backAction(imeVisible = true, handles = false))
        assertEquals(BackAction.Navigate, backAction(imeVisible = false, handles = true))
        assertEquals(BackAction.System, backAction(imeVisible = false, handles = false))
    }

    @Test fun everyBackHandlerGoesThroughTheKeyboardRule() {
        val source = File("src/main/java/com/evanaronson/linguize")
        val direct = Regex("""(?<![A-Za-z])BackHandler\(""")
        val offenders = source.walk().filter { it.isFile && it.extension == "kt" && it.name != "BackNavigation.kt" }
            .filter { direct.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        // Each window closes the keyboard on Back: the app, every popup card, the new-conversation sheet,
        // and the accessibility window, which gets Back as a key or callback rather than through Compose.
        assertTrue("KeyboardBackHandler()" in File(source, "ui/home/HomeActivity.kt").readText())
        // Over other apps a focused card field counts as the keyboard: Back never closes the card with it.
        assertTrue("KeyboardBackHandler(fieldCounts = true)" in File(source, "ui/card/FloatingCard.kt").readText())
        assertTrue("BackAction.CloseKeyboard" in File(source, "accessibility/OverlayWindow.kt").readText())
        // The new-conversation sheet and each dialog with a field are windows of their own, with their own Back.
        assertEquals(2, Regex("""KeyboardBackHandler\(fieldCounts = true\)""")
            .findAll(File(source, "ui/chat/ChatChrome.kt").readText()).count())
        assertTrue("KeyboardBackHandler(fieldCounts = true)" in File(source, "ui/words/WordDecks.kt").readText())
        // The popup activities hear the keyboard's insets on every Android version, through
        // enableEdgeToEdge, which also keeps the navigation buttons visible (light or dark icons).
        for (host in listOf("menu/CheckActivity.kt", "menu/ShareActivity.kt", "menu/ClipboardActivity.kt")) {
            val text = File(source, host).readText()
            assertTrue(host, "enableEdgeToEdge()" in text)
            assertFalse(host, "setDecorFitsSystemWindows" in text)
        }
    }

    @Test fun everyTextFieldReportsItsFocus() {
        val source = File("src/main/java/com/evanaronson/linguize")
        val fields = source.walk().filter { it.isFile && it.extension == "kt" }
            .filter { "BasicTextField(" in it.readText() }.toList()
        assertTrue(fields.isNotEmpty())
        for (file in fields) {
            assertEquals(file.name, Regex("""BasicTextField\(""").findAll(file.readText()).count(),
                Regex("""tracksFieldFocus\(\)""").findAll(file.readText()).count())
        }
    }
}
