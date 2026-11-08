package com.evanaronson.linguize.ui.chat

import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.core.UnderstandingNote
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.NewConversation
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap a reply to flip it to its translation and back; older replies fetch one once. */
class FlipsTest {
    private val asked = mutableListOf<String>()
    private var fail = false
    private val flips = Flips { text ->
        asked += text
        if (fail) throw CheckFailure(CheckFailure.Reason.Offline)
        Understood("How's your day going?", listOf(UnderstandingNote("¿Qué tal?", "A casual greeting")))
    }
    private val reply = ChatMessage.partner("r1", "c", "¿Qué tal tu día?", 1, translation = "How's your day?")
    private val older = ChatMessage.partner("r0", "c", "¿Qué tal?", 0)
    private val mine = ChatMessage("w", "c", true, "Bien", "Bien", at = 2)

    @Test fun aReplyFlipsToItsOwnTranslationAndBackWithoutAsking() {
        assertNull(flips.view(reply))
        assertFalse(flips.flip(reply))
        assertEquals(Flipped.Text("How's your day?"), flips.view(reply))
        assertFalse(flips.flip(reply))
        assertNull(flips.view(reply))
        assertTrue(asked.isEmpty())
    }

    @Test fun theWritersOwnMessagesDontFlip() {
        assertFalse(flips.flip(mine))
        assertNull(flips.view(mine))
        assertTrue(flips.flipped.isEmpty())
    }

    @Test fun anOlderReplyFetchesItsTranslationOnceAndKeepsIt() = runBlocking {
        assertTrue(flips.flip(older))
        assertEquals(Flipped.Loading, flips.view(older))
        flips.load(older)
        assertEquals(Flipped.Text("How's your day going?", listOf("¿Qué tal?: A casual greeting")), flips.view(older))
        flips.flip(older)
        assertFalse(flips.flip(older))
        assertTrue(flips.view(older) is Flipped.Text)
        assertEquals(listOf("¿Qué tal?"), asked)
    }

    @Test fun aFailedFetchShowsAndTheNextFlipTriesAgain() = runBlocking {
        fail = true
        flips.flip(older)
        flips.load(older)
        assertEquals(CheckFailure.Reason.Offline, (flips.view(older) as Flipped.Failed).failure.reason)
        fail = false
        flips.flip(older)
        assertTrue(flips.flip(older))
        flips.load(older)
        assertTrue(flips.view(older) is Flipped.Text)
    }

    @Test fun whoYouAreTalkingToIsOptional() {
        assertEquals(NewConversation(null, null), NewConversation.of("   ", null))
        assertEquals(NewConversation(null, "ca"), NewConversation.of("", "ca"))
        assertEquals(NewConversation("my kid's teacher", "es"), NewConversation.of("  my kid's   teacher ", "es"))
        assertEquals(NewConversation.MAX_PERSONA, NewConversation.of("x".repeat(500), null).persona?.length)
    }
}
