package com.evanaronson.linguize.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.llm.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keys saved under a provider's name by earlier builds are moved to its token, as they're
 * stored. (Robolectric has no Android Keystore, so nothing here encrypts or decrypts.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApiKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)

    @Test
    fun aKeySavedUnderTheProvidersNameIsMovedAndNotLost() {
        prefs.edit().putString("Gemini", "sealed-gemini").commit()
        val keys = ApiKeys(context)
        assertEquals("sealed-gemini", prefs.getString("gemini", null))
        assertFalse(prefs.contains("Gemini"))
        // The moved key is the one the settings screen reports on.
        assertNotEquals(KeyStatus.None, keys.status(Provider.Gemini))
        assertEquals(KeyStatus.None, keys.status(Provider.OpenAI))
    }

    @Test
    fun aKeyAlreadyUnderTheTokenWins() {
        prefs.edit().putString("OpenAI", "sealed-old").putString("openai", "sealed-new").commit()
        ApiKeys(context)
        assertEquals("sealed-new", prefs.getString("openai", null))
        assertFalse(prefs.contains("OpenAI"))
    }

    @Test
    fun nothingSavedMeansNoKeys() {
        val keys = ApiKeys(context)
        assertEquals(KeyStatus.None, keys.status(Provider.Gemini))
        assertEquals(null, keys.get(Provider.Gemini))
        assertEquals(emptyMap<String, Any?>(), prefs.all)
    }
}
