package com.evanaronson.linguize.ui.write

import com.evanaronson.linguize.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lab, Language scope: Write checks in the app's current language unless the picker changes it. */
class WriteLanguageTest {
    @Test fun theAppsLanguageIsTheDefault() {
        assertEquals(Language.Catalan, writeCheckLanguage("ca", null))
        assertEquals(Language.Spanish, writeCheckLanguage("es", null))
        // A language no check can be pinned to falls back to auto-detect.
        assertNull(writeCheckLanguage("en", null))
    }

    @Test fun thePickerChangesItIncludingToAutoDetect() {
        assertEquals(Language.Spanish, writeCheckLanguage("ca", pickedToken(Language.Spanish)))
        assertNull(writeCheckLanguage("ca", pickedToken(null)))
    }
}
