package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What a sent message keeps, and what of it reaches the writer corpus. */
class ChatRecordTest {
    private val conversation = Conversation("c1", "Weekend", "ca", createdAt = 1, updatedAt = 5)

    @Test fun theCorpusHoldsOnlyRawWriterTextWithFixSpansMarked() {
        val writer = ChatMessage(
            id = "m2", conversationId = "c1", fromWriter = true,
            raw = "Ahir vaig anar a la platja", text = "Ahir vam anar a la platja",
            changes = listOf(
                ChatChange(EditKind.Fix, 5, 9, "vaig", "vam", "Plural"),
                ChatChange(EditKind.Natural, 10, 14, "anar", "anar", null),
            ),
            check = SentCheck.Corrected, at = 3,
        )
        val partner = ChatMessage.partner("m1", "c1", "Hola! Què tal?", 2)
        val asWritten = ChatMessage("m3", "c1", true, "Molt bé", "Molt bé", check = SentCheck.AsWritten, at = 4)

        val utterances = ChatCorpus.utterances(listOf(conversation to listOf(partner, writer, asWritten)))

        assertEquals(listOf("chat:m2", "chat:m3"), utterances.map { it.id })
        val first = utterances.first()
        assertEquals("chat", first.source)
        assertEquals("ca", first.language)
        assertEquals("Ahir vaig anar a la platja", first.text)
        // Rewordings are not mistakes: only the fix marks words as tried and wrong.
        assertEquals(listOf(Span(5, 9)), first.corrected)
        assertTrue(first.isCorrected(5, 9))
        assertEquals(3L, first.at)
        assertTrue(utterances.none { it.text.contains("Què tal") || it.text == "Ahir vam anar a la platja" })
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

    @Test fun liveCheckingKeepsWhatWasTypedUnderAcceptedChanges() {
        val record = LiveRecord()
        val typed = "Ahir vaig anar platja"
        record.edited(typed)
        val fix = LiveSuggestion(1, EditKind.Fix, 5, 9, "vaig", "vam", "Plural")
        val afterFix = "Ahir vam anar platja"
        record.accepted(typed, afterFix, fix)
        val insertion = LiveSuggestion(2, EditKind.Fix, 13, 13, "", " a la", null)
        val afterInsertion = "Ahir vam anar a la platja"
        record.accepted(afterFix, afterInsertion, insertion)
        // The writer keeps typing after the accepted changes.
        val draft = "$afterInsertion amb amics"
        record.edited(draft)

        val outgoing = record.outgoing("  $draft ")
        assertEquals("Ahir vaig anar platja amb amics", outgoing.raw)
        assertEquals(draft, outgoing.text)
        assertEquals(SentCheck.Corrected, outgoing.check)
        assertEquals(
            listOf(ChatChange(EditKind.Fix, 5, 9, "vaig", "vam", "Plural"), ChatChange(EditKind.Fix, 14, 14, "", " a la", null)),
            outgoing.changes,
        )
        outgoing.message("m", "c", 1) // The record is consistent.
    }

    @Test fun typingIntoAnAcceptedChangeMakesItTheWritersOwn() {
        val record = LiveRecord()
        record.edited("Ahir vaig anar")
        record.accepted("Ahir vaig anar", "Ahir vam anar", LiveSuggestion(1, EditKind.Fix, 5, 9, "vaig", "vam", null))
        record.edited("Ahir vamos anar")
        val outgoing = record.outgoing("Ahir vamos anar")
        assertEquals("Ahir vamos anar", outgoing.raw)
        assertEquals(emptyList<ChatChange>(), outgoing.changes)
        assertEquals(SentCheck.None, outgoing.check)
    }

    @Test fun aRewordingOverAnAcceptedFixRevertsToTheFirstTyping() {
        val record = LiveRecord()
        record.edited("Fa molt calor avui")
        record.accepted("Fa molt calor avui", "Fa molta calor avui", LiveSuggestion(1, EditKind.Fix, 3, 7, "molt", "molta", null))
        record.accepted("Fa molta calor avui", "Quina calor avui",
            LiveSuggestion(2, EditKind.Natural, 0, 14, "Fa molta calor", "Quina calor", null))
        val outgoing = record.outgoing("Quina calor avui")
        assertEquals("Fa molt calor avui", outgoing.raw)
        assertEquals(listOf(ChatChange(EditKind.Fix, 0, 13, "Fa molt calor", "Quina calor", null)), outgoing.changes)
    }
}
