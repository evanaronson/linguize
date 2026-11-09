package com.evanaronson.linguize.ui.notes

import com.evanaronson.linguize.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab, Language scope: Notes checks in the app's current language; Linguize (Auto) detects. */
class NoteLanguageTest {
    @Test fun theAppsLanguageDecides() {
        assertEquals(Language.Catalan, noteCheckLanguage("ca"))
        assertEquals(Language.Spanish, noteCheckLanguage("es"))
        // Linguize detects, and so does a language no check can be pinned to.
        assertNull(noteCheckLanguage(null))
        assertNull(noteCheckLanguage("en"))
    }
}
