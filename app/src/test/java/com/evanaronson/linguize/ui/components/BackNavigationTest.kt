package com.evanaronson.linguize.ui.components

import org.junit.Assert.assertEquals
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
        assertTrue("KeyboardBackHandler()" in File(source, "ui/card/FloatingCard.kt").readText())
        assertTrue("KeyboardBackHandler()" in File(source, "ui/chat/ChatChrome.kt").readText())
        assertTrue("BackAction.CloseKeyboard" in File(source, "accessibility/OverlayWindow.kt").readText())
    }
}
