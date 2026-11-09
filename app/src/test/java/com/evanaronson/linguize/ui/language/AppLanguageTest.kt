package com.evanaronson.linguize.ui.language

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.data.language.LanguageChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's one language selector: Linguize (Auto) by default, then Catalanize and Castilianize; a choice is kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLanguageTest {
    private class Choice(override var current: String? = null) : LanguageChoice

    private val profiles = listOf(
        WritingProfile.defaultFor(Language.Catalan),
        WritingProfile.defaultFor(Language.Spanish).copy(variety = "Mexican"),
    )

    private fun language(choice: Choice, data: List<String> = emptyList()) = AppLanguage(choice, { profiles }, { data })

    @Test fun linguizeIsTheDefaultAndNothingIsStoredUntilAChoice() = runBlocking {
        val choice = Choice()
        val app = language(choice)
        assertNull(app.current.value)
        app.load()
        assertNull(app.current.value)
        assertNull(choice.current)
    }

    @Test fun aLanguageStoredBeforeAutoExistedIsKeptAsItIs() {
        assertEquals("ca", language(Choice("ca")).current.value)
    }

    @Test fun aChoiceIsKeptAndWinsNextTimeIncludingLinguize() = runBlocking {
        val choice = Choice()
        val app = language(choice)
        app.select("ca")
        assertEquals("ca", app.current.value)
        assertEquals("ca", choice.current)
        assertEquals("ca", language(choice).current.value)

        app.select(null)
        assertNull(app.current.value)
        assertEquals("auto", choice.current)
        // A new start reads Linguize at once.
        assertNull(language(choice).current.value)
    }

    @Test fun theSelectorOffersCatalanizeCastilianizeWithTheVarietyThenOthersInTheData() = runBlocking {
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

    @Test fun aConversationsLanguageIsFollowedOnlyWhenALanguageIsChosen() {
        val auto = language(Choice())
        auto.follow("es")
        assertNull(auto.current.value)
        val catalan = language(Choice("ca"))
        catalan.follow("es")
        assertEquals("es", catalan.current.value)
    }

    @Test fun theChoicesAreCalledByTheirBrandNames() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun name(code: String?) = brandNameResource(code)?.let(context::getString)
        assertEquals("Linguize", name(null))
        assertEquals("Catalanize", name("ca"))
        assertEquals("Castilianize", name("es"))
        // Any other language is called by its own name, with a code badge.
        assertNull(brandNameResource("en"))
        assertNull(flagFor("en", null))
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
