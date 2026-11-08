package com.evanaronson.linguize.data.writingpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000L
    private fun file() = File(folder.root, DraftStore.FILE_NAME)
    private fun store() = DraftStore(file()) { now }

    @Test fun draftsSurviveANewStoreAndListNewestFirst() {
        val store = store()
        val first = store.create()
        now = 2_000
        val second = store.create(language = "ca")
        now = 3_000
        store.save(first.id, "Landlord", "Hola Joan,\nLa calefacció no funciona.", "ca")

        val reopened = store()
        assertEquals(listOf(first.id, second.id), reopened.all().map { it.id })
        val saved = reopened.get(first.id)!!
        assertEquals("Landlord", saved.title)
        assertEquals("ca", saved.language)
        assertEquals(3_000L, saved.updatedAt)
        assertEquals(1_000L, saved.createdAt)
    }

    @Test fun savingTheSameContentDoesNotTouchTheDate() {
        val store = store()
        val draft = store.create()
        now = 5_000
        store.save(draft.id, "", "Hola", null)
        now = 9_000
        store.save(draft.id, "", "Hola", null)
        assertEquals(5_000L, store.get(draft.id)!!.updatedAt)
    }

    @Test fun deleteAndRestore() {
        val store = store()
        val draft = store.create()
        store.save(draft.id, "T", "Text", null)
        val saved = store.get(draft.id)!!
        store.delete(draft.id)
        assertNull(store.get(draft.id))
        assertNull(store.save(draft.id, "T", "again", null))
        store.restore(saved)
        assertEquals(saved, store().get(draft.id))
    }

    @Test fun anUnreadableFileIsSetAsideNotOverwritten() {
        file().writeText("{ not json")
        val store = store()
        assertEquals(emptyList<Draft>(), store.all())
        assertTrue(File(folder.root, DraftStore.FILE_NAME + ".unreadable").exists())
        store.create()
        assertEquals(1, store().all().size)
    }

    @Test fun noTemporaryFileIsLeftBehind() {
        store().create()
        assertEquals(listOf(DraftStore.FILE_NAME), folder.root.list()!!.toList())
    }

    @Test fun displayTitleFallsBackToTheFirstLine() {
        val draft = Draft("id", createdAt = 0, updatedAt = 0)
        assertNull(draft.displayTitle())
        assertEquals("Hola Joan,", draft.copy(text = "\n  Hola Joan,\nLa calefacció").displayTitle())
        assertEquals("Landlord", draft.copy(title = " Landlord ", text = "Hola").displayTitle())
        assertEquals("abcde…", draft.copy(text = "abcdefgh").displayTitle(maxChars = 5))
        assertTrue(draft.isEmpty)
    }
}
