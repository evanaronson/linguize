package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lab, Chat tab IA: what a sent message and a conversation row say about themselves. */
class ChatLabelsTest {
    @Test fun aFixMakesItCorrected() {
        val changes = listOf(ChatChange(EditKind.Natural, 0, 2, "ab", "cd"), ChatChange(EditKind.Fix, 3, 4, "x", "y"))
        assertEquals(ChatLabels.Sent.Corrected, ChatLabels.sent(changes))
    }

    @Test fun rewordingsAloneAreNotCorrections() {
        val changes = listOf(ChatChange(EditKind.Natural, 0, 2, "ab", "cd"))
        assertEquals(ChatLabels.Sent.Reworded, ChatLabels.sent(changes))
    }

    @Test fun aRowDescriptionSaysEverythingTheRowShowsInOrder() {
        assertEquals(
            "Rent, With Jordi, Catalan, 10:04, You: Ja ho tinc",
            ChatLabels.rowDescription("Rent", "With Jordi", "Catalan", "10:04", "You: Ja ho tinc"),
        )
        assertEquals("Rent, Catalan, 10:04", ChatLabels.rowDescription("Rent", null, "Catalan", "10:04", null))
        assertEquals("Rent, Catalan, 10:04", ChatLabels.rowDescription("Rent", " ", "Catalan", "10:04", ""))
    }
}
