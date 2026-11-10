package com.evanaronson.linguize.data.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WriteDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, WriteDraftStore.FILE_NAME)
    private val pad get() = File(folder.root, WriteDraftStore.PAD_FILE_NAME)

    private fun store() = WriteDraftStore(file, legacy = pad)

    @Test fun startsEmpty() {
        val drafts = store().load()
        assertNull(drafts.currentId)
        assertTrue(drafts.drafts.isEmpty())
    }

    @Test fun savedDraftsReadBackNewestFirstWithTheOpenOne() {
        store().apply {
            saveCurrent(WriteDraft("a", "Hola", "ca", updatedAt = 1))
            saveCurrent(WriteDraft("b", "Bon dia", null, updatedAt = 2))
        }
        val read = store().load()
        assertEquals("b", read.currentId)
        assertEquals(listOf("b", "a"), read.drafts.map { it.id })
        assertEquals(listOf("a"), read.others.map { it.id })
        assertEquals("ca", read.drafts.last().language)
    }

    @Test fun savingTheOpenDraftReplacesIt() {
        val store = store()
        store.saveCurrent(WriteDraft("a", "Hola", updatedAt = 1))
        store.saveCurrent(WriteDraft("a", "Hola, com va?", updatedAt = 2))
        assertEquals(listOf("Hola, com va?"), store().load().drafts.map { it.text })
    }

    @Test fun noOpenDraftKeepsTheOthersListed() {
        val store = store()
        store.saveCurrent(WriteDraft("a", "Hola", updatedAt = 1))
        store.setCurrent(null)
        val read = store().load()
        assertNull(read.current)
        assertEquals(listOf("a"), read.others.map { it.id })
    }

    @Test fun deleteAndRestore() {
        val store = store()
        val a = WriteDraft("a", "Hola", updatedAt = 1)
        store.saveCurrent(a)
        store.saveCurrent(WriteDraft("b", "Adéu", updatedAt = 2))
        store.delete("a")
        assertEquals(listOf("b"), store().load().drafts.map { it.id })
        store.restore(a)
        val read = store().load()
        assertEquals(listOf("b", "a"), read.drafts.map { it.id })
        assertEquals("b", read.currentId)
    }

    @Test fun deletingTheOpenDraftLeavesNoneOpen() {
        val store = store()
        store.saveCurrent(WriteDraft("a", "Hola", updatedAt = 1))
        store.delete("a")
        assertNull(store().load().currentId)
    }

    @Test fun anUnreadableFileIsSetAsideNotOverwritten() {
        file.writeText("{not json")
        val store = store()
        assertTrue(store.load().drafts.isEmpty())
        assertTrue(File(folder.root, WriteDraftStore.FILE_NAME + ".unreadable").exists())
        store.saveCurrent(WriteDraft("a", "Hola", updatedAt = 1))
        assertEquals("{not json", File(folder.root, WriteDraftStore.FILE_NAME + ".unreadable").readText())
    }

    @Test fun writingPadDraftsAreImportedOnceAndTheirFileIsLeftAlone() {
        val padJson = """
            {"version":1,"drafts":[
              {"id":"old","title":"","text":"Hola veí","language":"ca","createdAt":1,"updatedAt":10},
              {"id":"new","title":"Escola","text":"Bon dia,\nEl meu fill...","language":null,"createdAt":2,"updatedAt":20},
              {"id":"empty","title":"Res","text":"  ","createdAt":3,"updatedAt":30}
            ]}
        """.trimIndent()
        pad.writeText(padJson)

        val read = store().load()
        assertEquals("new", read.currentId)
        assertEquals(listOf("new", "old"), read.drafts.map { it.id })
        assertEquals("Escola", read.current?.label())
        assertEquals("Hola veí", read.others.single().label())
        assertEquals("ca", read.others.single().language)
        assertEquals(padJson, pad.readText())
        assertTrue(file.exists())

        // Once imported, Write's own file is the truth: deleting a draft doesn't bring it back.
        store().delete("old")
        assertEquals(listOf("new"), store().load().drafts.map { it.id })
    }

    @Test fun anUnreadablePadFileImportsNothing() {
        pad.writeText("garbage")
        assertTrue(store().load().drafts.isEmpty())
        assertEquals("garbage", pad.readText())
    }

    @Test fun anIoReadFailureIsNotAnEmptyStoreAndCanBeRetried() {
        assertTrue(file.mkdir())
        File(file, "keep").writeText("existing content")
        val store = store()

        assertThrows(IOException::class.java) { store.load() }
        assertEquals("existing content", File(file, "keep").readText())
        assertTrue(!File(folder.root, WriteDraftStore.FILE_NAME + ".unreadable").exists())

        assertTrue(file.deleteRecursively())
        val draft = WriteDraft("a", "Hola", updatedAt = 1)
        store().saveCurrent(draft)
        assertEquals(draft, store.load().current)
    }

    @Test fun aFailedWritingPadImportCanRetryWithoutChangingItsSource() {
        val original = """{"drafts":[{"id":"old","text":"Hola veí","updatedAt":10}]}"""
        pad.writeText(original)
        val temp = File(folder.root, WriteDraftStore.FILE_NAME + ".tmp")
        assertTrue(temp.mkdir())
        File(temp, "keep").writeText("blocks the write")
        val store = store()

        assertThrows(IOException::class.java) { store.load() }
        assertEquals(original, pad.readText())
        assertTrue(!file.exists())

        assertTrue(temp.deleteRecursively())
        assertEquals("Hola veí", store.load().current?.text)
        assertEquals(store.load(), store().load())
        assertEquals(original, pad.readText())
    }

    @Test fun quarantiningAnotherMalformedFilePreservesTheEarlierBackup() {
        file.writeText("first malformed file")
        assertTrue(store().load().drafts.isEmpty())
        file.writeText("second malformed file")
        assertTrue(store().load().drafts.isEmpty())

        assertEquals("first malformed file", File(folder.root, WriteDraftStore.FILE_NAME + ".unreadable").readText())
        assertEquals("second malformed file", File(folder.root, WriteDraftStore.FILE_NAME + ".unreadable.1").readText())
    }

    @Test fun theLabelIsTheTitleOrTheFirstLineShortened() {
        assertEquals("Hola", WriteDraft("a", "\n  Hola  \nAdéu", updatedAt = 0).label())
        assertEquals("Escola", WriteDraft("a", "Hola", title = "Escola", updatedAt = 0).label())
        assertEquals("abcde…", WriteDraft("a", "abcdefghij", updatedAt = 0).label(maxChars = 5))
    }
}
