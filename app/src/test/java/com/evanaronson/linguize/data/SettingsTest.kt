package com.evanaronson.linguize.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
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
}
