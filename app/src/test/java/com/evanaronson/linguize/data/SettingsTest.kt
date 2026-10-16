package com.evanaronson.linguize.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.WritingRegister
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.evanaronson.linguize.llm.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings as earlier builds saved them still read, and new ones are saved as tokens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Test
    fun writingProfilesMigrateLegacyPunctuationExactlyOnce() {
        prefs.edit().putString("punctuation", "Strict").commit()
        val settings = Settings(context)
        val migrated = settings.writingProfiles
        assertEquals(Language.all.map { WritingProfile.defaultFor(it, Punctuation.Strict) }, migrated)
        settings.punctuation = Punctuation.Casual
        assertEquals(migrated, Settings(context).writingProfiles)
        assertEquals(listOf("default-ca", "default-es"), migrated.map { it.id })
        assertEquals(listOf("Catalan", "Spanish"), migrated.map { it.name })
    }

    @Test
    fun writingProfilesRoundTripIndependentlyAsCompleteRecords() {
        val settings = Settings(context)
        val spanish = settings.writingProfiles.single { it.language == "es" }
        val catalan = settings.writingProfiles.single { it.language == "ca" }.copy(
            name = "Catalan chat",
            variety = "Girona",
            register = WritingRegister.Casual,
            punctuation = Punctuation.Strict,
        )
        settings.updateWritingProfile(catalan)
        val restored = Settings(context).writingProfiles
        assertEquals(listOf(catalan, spanish), restored)
        assertEquals(Punctuation.Moderate, settings.punctuation)
        val raw = prefs.getString("writingProfiles.v1", null)!!
        assertEquals(restored, Json.decodeFromString<List<WritingProfile>>(raw))
        org.junit.Assert.assertTrue(raw.contains("\"register\":\"casual\""))
        org.junit.Assert.assertTrue(raw.contains("\"punctuation\":\"strict\""))
    }

    @Test
    fun malformedProfileDoesNotEraseOtherLanguageOrUnrelatedSettings() {
        val spanish = WritingProfile.defaultFor(Language.Spanish).copy(register = WritingRegister.Formal)
        val valid = Json.encodeToString(spanish)
        prefs.edit().putString("writingProfiles.v1", "[{\"language\":\"ca\",\"register\":\"future-value\"},$valid]")
            .putString("model.gemini", "keep-this-model").commit()
        val settings = Settings(context)
        assertEquals(listOf(WritingProfile.defaultFor(Language.Catalan), spanish), settings.writingProfiles)
        assertEquals("keep-this-model", settings.model(Provider.Gemini))
        settings.updateWritingProfile(WritingProfile.defaultFor(Language.Catalan).copy(register = WritingRegister.Casual))
        assertEquals(spanish, settings.writingProfiles.single { it.language == "es" })
        assertEquals("keep-this-model", settings.model(Provider.Gemini))
    }

    @Test
    fun additionalNamedProfilesSurviveDefaultProfileEdits() {
        val first = WritingProfile.defaultFor(Language.Catalan)
        val work = first.copy(id = "catalan-work", name = "Catalan work", register = WritingRegister.Formal)
        prefs.edit().putString("writingProfiles.v1", Json.encodeToString(listOf(first, work))).commit()
        val settings = Settings(context)
        settings.updateWritingProfile(first.copy(register = WritingRegister.Casual))
        val stored = Json.decodeFromString<List<WritingProfile>>(prefs.getString("writingProfiles.v1", null)!!)
        assertEquals(work, stored.last())
        assertEquals(listOf("ca", "es"), Settings(context).writingProfiles.map { it.language })
    }

    @Test
    fun corruptProfileStateDefaultsWithoutRewritingStoredData() {
        prefs.edit().putString("writingProfiles.v1", "not JSON").putString("punctuation", "strict").commit()
        assertEquals(Language.all.map { WritingProfile.defaultFor(it) }, Settings(context).writingProfiles)
        assertEquals("not JSON", prefs.getString("writingProfiles.v1", null))
    }

    @Test
    fun futureLanguageRecordsSurviveSupportedLanguageEdits() {
        val future = WritingProfile("english-chat", "English chat", "en", "West Coast Californian", WritingRegister.Casual, Punctuation.Casual)
        prefs.edit().putString("writingProfiles.v1", Json.encodeToString(listOf(future))).commit()
        val settings = Settings(context)
        settings.updateWritingProfile(WritingProfile.defaultFor(Language.Catalan).copy(register = WritingRegister.Formal))
        val stored = Json.decodeFromString<List<WritingProfile>>(prefs.getString("writingProfiles.v1", null)!!)
        assertEquals(future, stored.last())
        assertEquals(listOf("ca", "es"), Settings(context).writingProfiles.map { it.language })
    }

    @Test
    fun popupPositionDefaultsToBottomForMissingOrUnknownValues() {
        assertEquals(PopupPosition.Bottom, Settings(context).popupPosition)
        prefs.edit().putString("popupPosition", "unknown").commit()
        assertEquals(PopupPosition.Bottom, Settings(context).popupPosition)
    }

    @Test
    fun everyPopupPositionPersistsAcrossSettingsInstances() {
        val settings = Settings(context)
        PopupPosition.entries.forEach { position ->
            settings.popupPosition = position
            assertEquals(position.token, prefs.getString("popupPosition", null))
            assertEquals(position, Settings(context).popupPosition)
        }
    }

    @Test
    fun choicesSavedByConstantNameStillRead() {
        prefs.edit().putString("provider", "OpenAI").putString("punctuation", "Strict").putString("judgments", "FixOnly").commit()
        val settings = Settings(context)
        assertEquals(Provider.OpenAI, settings.provider)
        assertEquals(Punctuation.Strict, settings.punctuation)
        assertEquals(Judgments.FixOnly, settings.judgments)
    }

    @Test
    fun choicesAreSavedAsTokens() {
        val settings = Settings(context)
        settings.provider = Provider.OpenAI
        settings.punctuation = Punctuation.Casual
        settings.judgments = Judgments.NaturalizeOnly
        assertEquals("openai", prefs.getString("provider", null))
        assertEquals("casual", prefs.getString("punctuation", null))
        assertEquals("naturalize", prefs.getString("judgments", null))
        assertEquals(Provider.OpenAI, Settings(context).provider)
    }

    @Test
    fun anythingUnknownReadsAsTheDefault() {
        prefs.edit().putString("provider", "anthropic").putString("punctuation", "Loose").commit()
        val settings = Settings(context)
        assertEquals(Provider.Gemini, settings.provider)
        assertEquals(Punctuation.Moderate, settings.punctuation)
        assertEquals(Judgments.Both, settings.judgments)
    }

    @Test
    fun aModelSavedUnderTheProvidersNameMovesToItsToken() {
        prefs.edit()
            .putString("model.Gemini", "gemini-old")
            .putString("model.OpenAI", "gpt-old")
            .putString("model.openai", "gpt-new")
            .commit()
        val settings = Settings(context)
        assertEquals("gemini-old", settings.model(Provider.Gemini))
        // One already under the token wins.
        assertEquals("gpt-new", settings.model(Provider.OpenAI))
        assertEquals("gemini-old", prefs.getString("model.gemini", null))
        assertFalse(prefs.contains("model.Gemini"))
        assertFalse(prefs.contains("model.OpenAI"))
        // Nothing is left to move the next time.
        assertEquals("gemini-old", Settings(context).model(Provider.Gemini))
    }

    @Test
    fun aModelIsSavedUnderTheProvidersToken() {
        val settings = Settings(context)
        settings.setModel(Provider.Gemini, "gemini-chosen")
        assertEquals("gemini-chosen", prefs.getString("model.gemini", null))
        settings.setModel(Provider.Gemini, null)
        assertNull(settings.model(Provider.Gemini))
    }

    @Test
    fun theNativeLanguageIsTheAppsOwn() {
        assertEquals("English", Settings(context).nativeLanguage)
    }

    @Test
    fun disablingHistoryRevokesOldSessionsAcrossSettingsInstances() {
        val first = Settings(context)
        val second = Settings(context)
        assertEquals(0L, first.historyEpoch)

        first.historyEnabled = false
        assertFalse(second.historyEnabled)
        assertEquals(1L, second.historyEpoch)
        second.historyEnabled = false
        assertEquals(1L, first.historyEpoch)

        second.historyEnabled = true
        assertEquals(1L, first.historyEpoch)
        first.historyEnabled = false
        assertEquals(2L, second.historyEpoch)
    }
}
