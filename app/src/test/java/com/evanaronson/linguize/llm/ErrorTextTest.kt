package com.evanaronson.linguize.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorTextTest {
    @Test
    fun openAiKeyFragmentsAndLinksAreCutOut() {
        val message = "Incorrect API key provided: sk-proj-********************abcd. " +
            "You can find your API key at https://platform.openai.com/account/api-keys."
        val scrubbed = scrubSecrets(message)
        assertFalse("sk-" in scrubbed)
        assertFalse("http" in scrubbed)
        assertEquals("Incorrect API key provided: …. You can find your API key at.", scrubbed)
    }

    @Test
    fun googleKeysAndUrlsCarryingThemAreCutOut() {
        val scrubbed = scrubSecrets("Bad key AIzaSyA1234567890abcdefgh in https://x.googleapis.com/v1/models?key=AIzaSyA1234567890abcdefgh failed")
        assertFalse("AIza" in scrubbed)
        assertFalse("googleapis" in scrubbed)
        assertEquals("Bad key … in failed", scrubbed)
    }

    @Test
    fun plainMessagesAreUntouched() {
        assertEquals("Quota exceeded for this project.", scrubSecrets("Quota exceeded for this project."))
    }

    @Test
    fun errorMessageIsScrubbedBeforeItIsKept() {
        val payload = """{"error":{"message":"Incorrect API key provided: sk-abcdef123456."}}"""
        assertEquals("Incorrect API key provided: ….", errorMessage(payload))
        assertNull(errorMessage("""{"error":{"message":"https://example.com/x"}}"""))
    }
}
