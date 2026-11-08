package com.evanaronson.linguize

import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.lab.ImageInput
import com.evanaronson.linguize.llm.lab.MultimodalRequest
import com.evanaronson.linguize.llm.lab.PhotoPrompt
import com.evanaronson.linguize.llm.lab.VisionClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PhotoUnderstanderTest {
    private val image = ImageInput("image/jpeg", byteArrayOf(1, 2, 3))
    private val answer = """{"legible":true,"language":"Spanish","transcription":"Cita previa","summary":"",""" +
        """"translation":"Appointment","actions":[],"words":[]}"""

    private class FakeVision(val answer: String) : VisionClient {
        val sent = mutableListOf<Triple<String, String, MultimodalRequest>>()
        override suspend fun generate(key: String, model: String, request: MultimodalRequest): String {
            sent += Triple(key, model, request)
            return answer
        }
    }

    private fun engine(prefs: FakePreferences, keys: Map<Provider, String>, clients: Map<Provider, VisionClient>) =
        PhotoUnderstander(prefs, ProviderKeys { keys[it] }, clients) {
            PhotoPrompt.load { File("src/main/assets/$it").readText() }
        }

    @Test fun sendsWithTheConfiguredProviderModelKeyAndNativeLanguage() = runBlocking {
        val gemini = FakeVision(answer)
        val openai = FakeVision(answer)
        val prefs = FakePreferences().apply {
            provider = Provider.OpenAI
            nativeLanguage = "Catalan"
            models[Provider.OpenAI] = "gpt-vision"
        }
        val reading = engine(prefs, mapOf(Provider.OpenAI to "o-key"), mapOf(Provider.Gemini to gemini, Provider.OpenAI to openai))
            .understand(image)
        assertEquals("Appointment", reading.translation)
        assertTrue(gemini.sent.isEmpty())
        val (key, model, request) = openai.sent.single()
        assertEquals("o-key", key)
        assertEquals("gpt-vision", model)
        assertTrue(request.user.contains("Catalan"))
        assertEquals(image, request.image)
    }

    @Test fun usesTheRecommendedModelWhenNoneIsChosen() = runBlocking {
        val gemini = FakeVision(answer)
        engine(FakePreferences(), mapOf(Provider.Gemini to "g-key"), mapOf(Provider.Gemini to gemini)).understand(image)
        assertEquals(Provider.Gemini.recommendedModel, gemini.sent.single().second)
    }

    @Test fun noKeyIsTheUsualNoKeyFailureAndNothingIsSent() = runBlocking {
        val gemini = FakeVision(answer)
        try {
            engine(FakePreferences(), emptyMap(), mapOf(Provider.Gemini to gemini)).understand(image)
            fail()
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.NoKey, e.reason)
        }
        assertTrue(gemini.sent.isEmpty())
    }

    @Test fun anEmptyImageIsNotSent() {
        val gemini = FakeVision(answer)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                engine(FakePreferences(), mapOf(Provider.Gemini to "g-key"), mapOf(Provider.Gemini to gemini))
                    .understand(ImageInput("image/jpeg", ByteArray(0)))
            }
        }
        assertTrue(gemini.sent.isEmpty())
    }

    @Test fun anUnexpectedClientErrorIsABadResponse() = runBlocking {
        val broken = VisionClient { _, _, _ -> throw IllegalStateException("boom") }
        try {
            engine(FakePreferences(), mapOf(Provider.Gemini to "g-key"), mapOf(Provider.Gemini to broken)).understand(image)
            fail()
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.BadResponse, e.reason)
        }
    }
}
