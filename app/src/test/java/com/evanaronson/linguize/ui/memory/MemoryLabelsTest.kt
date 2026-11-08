package com.evanaronson.linguize.ui.memory

import com.evanaronson.linguize.core.memory.Fact
import com.evanaronson.linguize.core.memory.FactCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryLabelsTest {
    private fun fact(id: String, category: FactCategory, at: Long, vararg sources: String, byWriter: Boolean = false, updated: Long = at) =
        Fact(id, "Fact $id", category, sources.toList(), at, updated, byWriter)

    @Test fun groupsFollowTheCategoriesOrderOldestFirst() {
        val groups = groupFacts(listOf(
            fact("p", FactCategory.Preferences, 1),
            fact("a2", FactCategory.About, 5),
            fact("a1", FactCategory.About, 2),
        ))
        assertEquals(listOf(FactCategory.About, FactCategory.Preferences), groups.map { it.category })
        assertEquals(listOf("a1", "a2"), groups.first().facts.map { it.id })
    }

    @Test fun originsNameTheChatsAndTheirDates() {
        val sources = mapOf("chat:1" to 10L, "chat:2" to 30L)
        assertEquals(FactOrigin.Writer(edited = false), originOf(fact("w", FactCategory.About, 1, byWriter = true), sources))
        assertEquals(FactOrigin.Writer(edited = true), originOf(fact("w", FactCategory.About, 1, byWriter = true, updated = 9), sources))
        assertEquals(FactOrigin.One(chat = true, at = 10, edited = false), originOf(fact("x", FactCategory.About, 10, "chat:1"), sources))
        assertEquals(FactOrigin.One(chat = false, at = 4, edited = true),
            originOf(fact("x", FactCategory.About, 4, "check:7", byWriter = true), sources))
        assertEquals(FactOrigin.Many(count = 2, latest = 30, edited = false),
            originOf(fact("y", FactCategory.About, 10, "chat:1", "chat:2"), sources))
    }
}
