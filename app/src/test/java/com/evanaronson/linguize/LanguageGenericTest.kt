package com.evanaronson.linguize

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import com.evanaronson.linguize.core.chat.ChatStart
import com.evanaronson.linguize.core.chat.Conversation
import com.evanaronson.linguize.core.chat.ConversationSummary
import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.MenuEntry
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.Settings
import com.evanaronson.linguize.data.language.LanguageChoice
import com.evanaronson.linguize.llm.FakeHttp
import com.evanaronson.linguize.llm.GeminiClient
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.ui.conjugations.CONJUGATION_LANGUAGES
import com.evanaronson.linguize.ui.conjugations.ConjugationsState
import com.evanaronson.linguize.ui.language.AppLanguage
import com.evanaronson.linguize.ui.progress.progressLanguages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lab, language-generic: a language the app never named (French) works end to end: added to
 * the writer's languages, it gets a writing profile, the selector lists it, a check carries its
 * profile, a conversation is in it, and Progress has honest empty states for it. Existing
 * Catalan and Spanish settings migrate untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LanguageGenericTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val french = Language.forCode("fr")!!

    private class Choice(override var current: String? = null) : LanguageChoice

    @Test fun existingWritersKeepCatalanAndSpanishAndTheirProfilesWithNothingRewritten() {
        val settings = Settings(context)
        assertEquals(listOf("ca", "es"), settings.myLanguages.codes)
        assertEquals(Language.all.map { WritingProfile.defaultFor(it) }, settings.writingProfiles)
        // Nothing is stored in Lab's file until the writer edits the list.
        val lab = context.getSharedPreferences("lab_lang_generic", Context.MODE_PRIVATE)
        assertTrue(lab.all.isEmpty())
    }

    @Test fun addingFrenchCreatesAnEverydayProfileAndRemovingCatalanKeepsItsStoredProfile() {
        val settings = Settings(context)
        val catalan = settings.writingProfiles.first().copy(register = WritingRegister.Casual)
        settings.updateWritingProfile(catalan)

        assertEquals(listOf("ca", "es", "fr"), settings.myLanguages.add("fr"))
        val profile = Settings(context).writingProfiles.single { it.language == "fr" }
        assertEquals(WritingProfile("default-fr", "French", "fr", null, WritingRegister.Everyday, profile.punctuation), profile)

        settings.updateWritingProfile(profile.copy(register = WritingRegister.Formal))
        assertEquals(WritingRegister.Formal, Settings(context).writingProfiles.single { it.language == "fr" }.register)

        settings.myLanguages.remove("ca")
        assertEquals(listOf("es", "fr"), Settings(context).writingProfiles.map { it.language })
        // Catalan's profile is still stored, and comes back as it was when Catalan is added again.
        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("writingProfiles.v1", null)!!
        assertTrue(Json.parseToJsonElement(raw).jsonArray.any { it.jsonObject["language"] == JsonPrimitive("ca") })
        settings.myLanguages.add("ca")
        assertEquals(catalan, Settings(context).writingProfiles.single { it.language == "ca" })
    }

    @Test fun aCheckInFrenchCarriesTheFrenchProfile() = runBlocking {
        val settings = Settings(context)
        settings.myLanguages.add("fr")
        val formal = settings.writingProfiles.single { it.language == "fr" }.copy(register = WritingRegister.Formal)
        settings.updateWritingProfile(formal)
        val client = FakeClient { ModelAnswer(QUESTION_VERDICT, QUESTION_JSON) }
        val prompt = Prompt("instructions", "{}")
        val checker = Checker(settings, { "key" }, prompt, Provider.entries.associateWith { client })

        checker.check("Je suis content.", french)

        val request = client.checks.first().third
        assertEquals("fr", request.language?.code)
        assertTrue(formal in request.writingProfiles)
        assertEquals(listOf("ca", "es", "fr"), request.writingProfiles.map { it.language })
        val message = request.generation.user
        assertTrue(message.startsWith("Language: French\n"))
        assertTrue(message.contains("\"language\":\"fr\""))
        // Without profiles, a language with no standard variety of its own is sent by name alone.
        assertTrue(prompt.userMessage(CheckRequest("Je suis content.", french, settings.punctuation, settings.judgments))
            .startsWith("Language: French\n"))
    }

    @Test fun theSelectorListsTheWritersLanguagesIncludingFrench() = runBlocking {
        val settings = Settings(context)
        val language = AppLanguage(Choice(), { settings.writingProfiles }, { listOf("de", "fr") }, settings.myLanguages)
        language.load()
        assertEquals(listOf("ca", "es"), language.options.value.map { it.code })
        assertEquals(listOf("de", "fr"), language.suggested.value)

        language.add("fr")
        assertEquals(listOf("ca", "es", "fr"), language.options.value.map { it.code })
        assertEquals(listOf("de"), language.suggested.value)
        assertEquals(listOf("ca", "es", "fr"), Settings(context).myLanguages.codes)
        language.select("fr")
        assertEquals("fr", language.current.value)

        language.remove("ca")
        assertEquals(listOf("es", "fr"), language.options.value.map { it.code })
    }

    @Test fun aConversationInFrenchStartsAndIsWrittenInFrench() = runBlocking {
        val settings = Settings(context)
        settings.myLanguages.add("fr")
        settings.myLanguages.remove("ca")
        settings.myLanguages.remove("es")
        assertEquals("fr", ChatStart.language(null, emptyList(), listOf("es"), settings.myLanguages.codes))
        assertEquals("Bonjour ! Comment se passe ta journée ?", ChatStart.fallbackOpener("fr"))
        assertEquals("Hello! How is your day going?", ChatStart.fallbackOpener("sw"))

        val raw = """{"reply":"Bonjour ! Ça va ?","title":""}"""
        val http = FakeHttp { _, _ ->
            200 to """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${JsonPrimitive(raw)}}]}}]}"""
        }
        val memory = object : UserMemory {
            override suspend fun forPrompt() = ""
            override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = true
            override suspend fun forget(source: String) = Unit
        }
        val chatPrompt = ChatPrompt.load { File("src/main/assets/$it").readText() }
        val partner = ChatPartner(settings, ProviderKeys { "key" }, mapOf(Provider.Gemini to GeminiClient(http.client)),
            { memory }) { chatPrompt }
        assertEquals("Bonjour ! Ça va ?", partner.open(french).reply)
        val body = http.body(0)
        assertTrue(body.contains("\\\"language\\\":\\\"French\\\""))
        assertTrue(body.contains("\\\"variety\\\":\\\"\\\""))
    }

    @Test fun progressAndTheTabsHaveEmptyStatesForFrench() {
        // Under Linguize with no data yet: a French writer's blocks are French.
        assertEquals(listOf("fr"), progressLanguages(null, emptyList(), listOf("fr")))
        // With data, the languages that have some (as before): French appears once it has any.
        assertEquals(listOf("es"), progressLanguages(null, listOf("es"), listOf("fr", "es")))
        assertEquals(listOf("fr", "es"), progressLanguages(null, listOf("es", "fr"), listOf("fr", "es")))
        assertEquals(listOf("fr"), progressLanguages("fr", listOf("es"), listOf("ca", "es")))
        // Chat: no conversations; History and phrases: nothing kept.
        val spanish = ConversationSummary(Conversation("1", "Hola", "es", 0, 0), "Hola", 1)
        assertTrue(ChatStart.inLanguage(listOf(spanish), "fr").isEmpty())
        assertFalse(LanguageScope.keeps("es", "fr"))
        assertFalse(LanguageScope.keeps(null, "fr"))
        // Conjugations: not available for French yet, never a crash.
        val conjugations = ConjugationsState(loading = false, language = "fr")
        assertEquals("fr", conjugations.shown)
        assertFalse(conjugations.shown in CONJUGATION_LANGUAGES)
        assertNull(conjugations.report)
        // Chat's practice targets: nothing to steer toward without the Spanish and Catalan engines.
        val said = (1..12).map {
            com.evanaronson.linguize.core.corpus.Utterance("check:$it", "check", "fr", "Je travaille à la maison aujourd'hui.", at = it.toLong(), origin = "menu")
        }
        val targets = com.evanaronson.linguize.core.chat.PracticeTargetsBuilder.build(
            said, "fr", com.evanaronson.linguize.core.morphology.Morphology(), null, com.evanaronson.linguize.core.corpus.Lemmatizer.Identity)
        assertTrue(targets.cells.isEmpty() && targets.words.isEmpty())
        assertEquals("", targets.render())
        // The selection menu offers no French alias (labels are fixed), only Linguize.
        assertEquals(listOf(MenuEntry.Auto), MenuEntry.offered(listOf("fr"), setOf(MenuEntry.Auto)))
    }
}
