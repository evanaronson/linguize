package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PhotoPromptTest {
    private val prompt = PhotoPrompt.load { File("src/main/assets/$it").readText() }

    private fun answer(
        legible: Boolean = true,
        transcription: String = "Cal pagar 25 € abans del 15 de març.",
        translation: String = "You must pay €25 before 15 March.",
        actions: String = """[{"task":" Pay the school trip ","deadline":"abans del 15 de març","amount":"25 €"}]""",
        words: String = """[{"word":"abans de","explanation":"Before."}]""",
    ) = """{"legible":$legible,"language":" Catalan ","transcription":${quote(transcription)},""" +
        """"summary":"A school notice about a trip.","translation":${quote(translation)},"actions":$actions,"words":$words}"""

    private fun quote(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()

    private fun assertInvalid(raw: String) {
        try {
            prompt.read(raw)
            fail("Expected an invalid reply: $raw")
        } catch (e: CheckFailure) {
            assertEquals(CheckFailure.Reason.BadResponse, e.reason)
            assertEquals(CheckFailure.Message.InvalidReply, e.userMessage)
        }
    }

    @Test fun readsTheTranscriptionTranslationActionsAndWordsTrimmed() {
        val reading = prompt.read(answer())
        assertTrue(reading.legible)
        assertEquals("Catalan", reading.language)
        assertEquals("You must pay €25 before 15 March.", reading.translation)
        assertEquals(listOf(PhotoAction("Pay the school trip", "abans del 15 de març", "25 €")), reading.actions)
        assertEquals(listOf(PhotoWord("abans de", "Before.")), reading.words)
    }

    @Test fun dropsActionsAndWordsWithNothingInThem() {
        val reading = prompt.read(answer(
            actions = """[{"task":"  ","deadline":"demà","amount":""},{"task":"Sign the form","deadline":"","amount":""}]""",
            words = """[{"word":"","explanation":"x"},{"word":"AMPA","explanation":" "},{"word":"AMPA","explanation":"Parents' association."}]""",
        ))
        assertEquals(listOf(PhotoAction("Sign the form", "", "")), reading.actions)
        assertEquals(listOf(PhotoWord("AMPA", "Parents' association.")), reading.words)
    }

    @Test fun anUnreadablePhotoBecomesOneEmptyReadingWhateverElseCameBack() {
        val reading = prompt.read(answer(legible = false, transcription = "maybe something", translation = ""))
        assertEquals(PhotoPrompt.UNREADABLE, reading)
        assertFalse(reading.legible)
    }

    @Test fun aLegibleReadingNeedsBothTheTextAndItsTranslation() {
        assertInvalid(answer(transcription = " "))
        assertInvalid(answer(translation = ""))
    }

    @Test fun rejectsMalformedOrOversizedReplies() {
        assertInvalid("not json")
        assertInvalid("""{"legible":true}""")
        // Unknown fields mean the model ignored the schema.
        assertInvalid(answer().dropLast(1) + ""","extra":1}""")
        assertInvalid(answer(translation = "x".repeat(PhotoPrompt.MAX_TEXT_CHARS + 1)))
        val tooMany = (1..PhotoPrompt.MAX_ACTIONS + 1).joinToString(",", "[", "]") { """{"task":"Task $it","deadline":"","amount":""}""" }
        assertInvalid(answer(actions = tooMany))
        assertInvalid("{" + " ".repeat(PhotoPrompt.MAX_RAW_CHARS) + "}")
    }

    @Test fun theRequestCarriesTheNativeLanguageAsDataNotInstructions() {
        val image = ImageInput("image/jpeg", byteArrayOf(1))
        val request = prompt.request(image, "Catalan")
        assertEquals("""{"explainIn":"Catalan","task":"Read the text in the attached image."}""", request.user)
        assertTrue(request.system.contains("untrusted"))
        assertEquals("photo_reading", request.name)
        assertEquals(PhotoPrompt.MAX_OUTPUT_TOKENS, request.maxTokens)
    }
}
