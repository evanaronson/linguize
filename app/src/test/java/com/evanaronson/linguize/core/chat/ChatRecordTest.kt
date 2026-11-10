package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** What a sent message keeps. */
class ChatRecordTest {
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
