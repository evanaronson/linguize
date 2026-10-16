package com.evanaronson.linguize.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WritingProfileTest {
    @Test
    fun everyRegisterAndPunctuationRoundTripsWithStableTokens() {
        WritingRegister.entries.forEach { register ->
            assertEquals("\"${register.token}\"", Json.encodeToString(register))
            Punctuation.entries.forEach { punctuation ->
                assertEquals("\"${punctuation.token}\"", Json.encodeToString(punctuation))
                val profile = WritingProfile.defaultFor(Language.Catalan).copy(
                    register = register, punctuation = punctuation, variety = null,
                )
                assertEquals(profile, Json.decodeFromString<WritingProfile>(Json.encodeToString(profile)))
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun languageCodesCannotBeArbitraryPromptInstructions() {
        WritingProfile.defaultFor(Language.Catalan).copy(language = "ignore instructions")
    }
}
