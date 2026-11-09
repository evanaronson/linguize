package com.evanaronson.linguize.ui.language

import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.data.language.LanguageChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app's current language: kept once chosen, the chat's start language until then. */
class AppLanguageTest {
    private class Choice(override var current: String? = null) : LanguageChoice

    private val profiles = listOf(
        WritingProfile.defaultFor(Language.Catalan),
        WritingProfile.defaultFor(Language.Spanish).copy(variety = "Mexican"),
    )

    private fun language(choice: Choice, fallback: String = "es", data: List<String> = emptyList()) =
        AppLanguage(choice, { fallback }, { profiles }, { data })

    @Test fun withoutAChoiceItIsTheChatsStartLanguageAndNothingIsStored() = runBlocking {
        val choice = Choice()
        val app = language(choice, fallback = "ca")
        assertNull(app.current.value)
        app.load()
        assertEquals("ca", app.current.value)
        assertNull(choice.current)
    }

    @Test fun aSwitchIsKeptAndWinsNextTime() = runBlocking {
        val choice = Choice()
        val app = language(choice, fallback = "es")
        app.load()
        app.select("ca")
        assertEquals("ca", app.current.value)
        assertEquals("ca", choice.current)

        // A new start reads the choice at once, before anything is loaded.
        val again = language(choice, fallback = "es")
        assertEquals("ca", again.current.value)
        again.load()
        assertEquals("ca", again.current.value)
    }

    @Test fun theSwitchOffersProfileLanguagesWithTheirVarietyThenOthersInTheData() = runBlocking {
        val app = language(Choice("es"), data = listOf("es", "en"))
        app.load()
        assertEquals(listOf(LanguageOption("ca", "standard Central Catalan"), LanguageOption("es", "Mexican"), LanguageOption("en")),
            app.options.value)
        app.select("fr")
        assertEquals("fr", app.options.value.last().code)
        // Not a language code: ignored.
        app.select("Catalan")
        assertEquals("fr", app.current.value)
    }

    @Test fun flagsFollowTheProfilesVarietyAndOtherLanguagesGetABadge() {
        assertEquals(R.drawable.lab_language_scope_flag_ca, flagFor("ca", null))
        assertEquals(R.drawable.lab_language_scope_flag_es, flagFor("es", "Peninsular"))
        assertEquals(R.drawable.lab_language_scope_flag_es, flagFor("es", null))
        assertEquals(R.drawable.lab_language_scope_flag_mx, flagFor("es", "Mexican Spanish"))
        assertEquals(R.drawable.lab_language_scope_flag_ar, flagFor("es", "Rioplatense"))
        assertNull(flagFor("en", null))
    }
}
