package com.evanaronson.linguize.data.memory

import com.evanaronson.linguize.core.memory.FactCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 100L
    private var next = 0
    private fun store(file: File = File(folder.root, MemoryStore.FILE_NAME)) =
        MemoryStore(file, clock = { now }, newId = { "id${++next}" })

    @Test fun startsEmptyAndOn() {
        val state = store().load()
        assertTrue(state.enabled)
        assertTrue(state.facts.isEmpty())
    }

    @Test fun changesSurviveARestart() {
        val first = store()
        val fact = first.add("Lives in Gràcia, Barcelona", FactCategory.About)!!
        now = 200
        assertTrue(first.edit(fact.id, "Lives in Sants, Barcelona", FactCategory.About))
        first.setEnabled(false)

        val reopened = store().load()
        assertFalse(reopened.enabled)
        val saved = reopened.facts.single()
        assertEquals("Lives in Sants, Barcelona", saved.text)
        assertEquals(100L, saved.createdAt)
        assertEquals(200L, saved.updatedAt)
        assertTrue(saved.byWriter)
    }

    @Test fun deleteAndRestoreKeepOrder() {
        val store = store()
        val a = store.add("A", FactCategory.About)!!
        now = 200
        store.add("B", FactCategory.About)!!
        val deleted = store.delete(a.id)!!
        assertEquals(listOf("B"), store.load().facts.map { it.text })
        store.restore(deleted)
        store.restore(deleted)
        assertEquals(listOf("A", "B"), store.load().facts.map { it.text })
        assertNull(store.delete("missing"))
    }

    @Test fun clearKeepsTheSwitch() {
        val store = store()
        store.add("A", FactCategory.About)
        store.setEnabled(false)
        store.clear()
        val state = store().load()
        assertTrue(state.facts.isEmpty())
        assertFalse(state.enabled)
    }

    @Test fun anUnreadableFileIsSetAsideNotDeleted() {
        val file = File(folder.root, MemoryStore.FILE_NAME).apply { writeText("{not json") }
        assertTrue(store(file).load().facts.isEmpty())
        assertTrue(File(folder.root, MemoryStore.FILE_NAME + ".unreadable").exists())
    }

    @Test fun emptyTextIsNotANote() {
        val store = store()
        assertNull(store.add("   ", FactCategory.About))
        val fact = store.add("A", FactCategory.About)
        assertNotNull(fact)
        fact!!
        assertFalse(store.edit(fact.id, " ", FactCategory.About))
    }
}
