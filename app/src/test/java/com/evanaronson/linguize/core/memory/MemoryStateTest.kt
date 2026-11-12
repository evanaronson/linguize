package com.evanaronson.linguize.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStateTest {
    private var next = 0
    private val newId = { "n${++next}" }

    private fun fact(id: String, text: String, vararg sources: String, byWriter: Boolean = false, at: Long = 1,
                     category: FactCategory = FactCategory.About) =
        Fact(id, text, category, sources.toList(), at, at, byWriter)

    private val barcelona = fact("a", "Lives in Gràcia, Barcelona", "chat:1")
    private val jordi = fact("b", "Writes to their landlord, Jordi, formally", "chat:1", "chat:2", category = FactCategory.People)
    private val mine = fact("c", "Prefers casual Spanish", byWriter = true, category = FactCategory.Preferences)
    private val state = MemoryState(facts = listOf(barcelona, jordi, mine), sources = mapOf("chat:1" to 10L, "chat:2" to 20L))

    @Test fun addsNewFactsWithTheirSourceAndRemembersWhenItWasLearned() {
        val after = state.applying(
            MemoryChanges(add = listOf(NewFact("Preparing for a job interview in Catalan", FactCategory.Goals))),
            "chat:3", now = 30, newId = newId,
        )
        val added = after.facts.last()
        assertEquals("n1", added.id)
        assertEquals(listOf("chat:3"), added.sources)
        assertEquals(FactCategory.Goals, added.category)
        assertEquals(30L, added.createdAt)
        assertFalse(added.byWriter)
        assertEquals(30L, after.sources["chat:3"])
    }

    @Test fun replacementTextIsSupportedOnlyByItsNewSource() {
        val after = state.applying(
            MemoryChanges(update = listOf(FactUpdate("a", "Lives in Sants, Barcelona"))), "chat:3", 30, newId,
        )
        val updated = after.facts.first { it.id == "a" }
        assertEquals("Lives in Sants, Barcelona", updated.text)
        assertEquals(listOf("chat:3"), updated.sources)
        assertEquals(FactCategory.About, updated.category)
        assertEquals(1L, updated.createdAt)
        assertEquals(30L, updated.updatedAt)
    }

    @Test fun corroboratingTheSameClaimKeepsItsIndependentSources() {
        val original = state.facts.first { it.id == "a" }
        val after = state.applying(
            MemoryChanges(update = listOf(FactUpdate("a", original.text))), "chat:3", 30, newId,
        )
        assertEquals(listOf("chat:1", "chat:3"), after.facts.first { it.id == "a" }.sources)
        assertEquals(listOf("chat:1"), after.forgetting("chat:3").facts.first { it.id == "a" }.sources)
    }

    @Test fun removesById() {
        val after = state.applying(MemoryChanges(remove = listOf("b")), "chat:3", 30, newId)
        assertEquals(listOf("a", "c"), after.facts.map { it.id })
        // chat:2 now cites nothing, so its date goes too.
        assertFalse("chat:2" in after.sources)
    }

    @Test fun neverChangesOrRemovesTheWritersOwnFacts() {
        val after = state.applying(
            MemoryChanges(update = listOf(FactUpdate("c", "Prefers formal Spanish")), remove = listOf("c")),
            "chat:3", 30, newId,
        )
        assertSame(state, after)
        assertEquals(mine, after.facts.first { it.id == "c" })
    }

    @Test fun unknownIdsAndUnusableTextAreIgnored() {
        val after = state.applying(
            MemoryChanges(
                add = listOf(NewFact("  ", FactCategory.About), NewFact("x".repeat(141), FactCategory.About),
                    NewFact("Phone is 612 345 678", FactCategory.About)),
                update = listOf(FactUpdate("zzz", "Something")),
                remove = listOf("zzz"),
            ),
            "chat:3", 30, newId,
        )
        assertSame(state, after)
    }

    @Test fun aRepeatedFactMergesItsSourceInsteadOfDuplicating() {
        val after = state.applying(
            MemoryChanges(add = listOf(NewFact("lives in gràcia, barcelona.", FactCategory.About))), "chat:3", 30, newId,
        )
        assertEquals(3, after.facts.size)
        assertEquals(listOf("chat:1", "chat:3"), after.facts.first { it.id == "a" }.sources)

        val repeatsMine = state.applying(
            MemoryChanges(add = listOf(NewFact("Prefers casual Spanish", FactCategory.Preferences))), "chat:3", 30, newId,
        )
        assertSame(state, repeatsMine)
    }

    @Test fun additionsStopAtTheCapAndNothingIsPushedOut() {
        val full = MemoryState(facts = (1..MemoryState.MAX_FACTS - 1).map { fact("f$it", "Fact $it", "chat:1") })
        val after = full.applying(
            MemoryChanges(add = listOf(NewFact("One more", FactCategory.About), NewFact("And another", FactCategory.About))),
            "chat:2", 30, newId,
        )
        assertEquals(MemoryState.MAX_FACTS, after.facts.size)
        assertEquals("One more", after.facts.last().text)
        assertTrue(full.facts.all { it in after.facts })
    }

    @Test fun textIsTidied() {
        val after = MemoryState().applying(
            MemoryChanges(add = listOf(NewFact("  Works   as a nurse. ", FactCategory.About))), "chat:1", 1, newId,
        )
        assertEquals("Works as a nurse", after.facts.single().text)
    }

    @Test fun forgettingASourceKeepsFactsWithOtherSourcesOrByTheWriter() {
        val edited = fact("d", "Has a dog called Bru", "chat:1", byWriter = true)
        val after = state.copy(facts = state.facts + edited).forgetting("chat:1")
        assertEquals(listOf("b", "c", "d"), after.facts.map { it.id })
        assertEquals(listOf("chat:2"), after.facts.first { it.id == "b" }.sources)
        assertEquals(emptyList<String>(), after.facts.first { it.id == "d" }.sources)
        assertFalse("chat:1" in after.sources)
    }

    @Test fun forgettingAnUnknownSourceChangesNothing() {
        assertEquals(state, state.forgetting("chat:99"))
    }

    @Test fun theWritersOwnFactsAndEdits() {
        val (added, fact) = MemoryState().adding(" My number is 612 345 678 ", FactCategory.About, 5, newId)!!
        assertTrue(fact.byWriter)
        assertEquals("My number is 612 345 678", fact.text)
        assertEquals(emptyList<String>(), fact.sources)
        assertNull(MemoryState().adding("   ", FactCategory.About, 5, newId))

        val edited = state.editing("a", "Lives in Sants", FactCategory.About, 50)!!.facts.first { it.id == "a" }
        assertTrue(edited.byWriter)
        assertEquals(listOf("chat:1"), edited.sources)
        assertEquals(50L, edited.updatedAt)
        assertNull(state.editing("zzz", "x", FactCategory.About, 50))
        assertEquals(1, added.facts.size)
    }

    @Test fun promptBlockIsGroupedStableAndEmptyWhenOffOrEmpty() {
        val block = state.promptBlock()
        assertEquals(
            listOf(MemoryState.PROMPT_HEADER, "- Lives in Gràcia, Barcelona",
                "- Writes to their landlord, Jordi, formally", "- Prefers casual Spanish"),
            block.lines(),
        )
        assertEquals(block, state.copy(facts = state.facts.reversed()).promptBlock())
        assertEquals("", state.copy(enabled = false).promptBlock())
        assertEquals("", MemoryState().promptBlock())
    }

    @Test fun promptBlockIsBounded() {
        val many = MemoryState(facts = (1..200).map { fact("f$it", "x".repeat(120) + it, "chat:1", at = it.toLong()) })
        assertTrue(many.promptBlock().length <= MemoryState.MAX_PROMPT_CHARS)
    }
}
