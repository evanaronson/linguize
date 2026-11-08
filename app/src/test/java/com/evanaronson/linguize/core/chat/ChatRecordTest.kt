package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** What a sent message keeps. */
class ChatRecordTest {
    @Test fun aMessageKeepsRawTextAndTheSpansItsFixesReplaced() {
        val message = ChatMessage(
            id = "m2", conversationId = "c1", fromWriter = true,
            raw = "Ahir vaig anar a la platja", text = "Ahir vam anar a la platja",
            changes = listOf(
                ChatChange(EditKind.Fix, 5, 9, "vaig", "vam", "Plural"),
                ChatChange(EditKind.Natural, 10, 14, "anar", "anar", null),
            ),
            check = SentCheck.Corrected, at = 3, sessionId = "s1",
        )
        // Rewordings are not mistakes: only the fix marks words as tried and wrong.
        assertEquals(listOf(Span(5, 9)), message.correctedSpans)
        assertEquals("s1", message.sessionId)
    }

    @Test fun aChangeMustHoldItsTextInTheRawMessage() {
        try {
            ChatMessage("m", "c", true, "Hola", "Hola!", listOf(ChatChange(EditKind.Fix, 0, 2, "xx", "Ho")), SentCheck.Corrected, 1)
            fail("A change that doesn't match the raw text must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun titlesComeFromTheFirstLineCutAtAWord() {
        assertEquals("Vull anar al cinema", deriveTitle("  Vull anar al cinema\nAvui"))
        assertEquals("Demà tinc una entrevista de feina a…", deriveTitle("Demà tinc una entrevista de feina a Barcelona i estic nerviós"))
        assertEquals("", deriveTitle("   "))
    }
}
