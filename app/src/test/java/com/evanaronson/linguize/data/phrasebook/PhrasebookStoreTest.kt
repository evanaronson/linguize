package com.evanaronson.linguize.data.phrasebook

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhrasebookStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000L
    private var ids = 0
    private val file get() = File(folder.root, PhrasebookStore.FILE_NAME)
    private fun store(file: File = this.file) = PhrasebookStore(file, clock = { now++ }, newId = { "id${ids++}" })

    private val meVaBien = PhraseDraft(
        phrase = "  me va   bien ", replaced = "es bueno para mí", meaning = "Sounds natural for plans that suit you.",
        language = "es", source = PhraseSource.Rewording, app = "com.whatsapp",
    )

    @Test fun nothingIsReadUntilLoadedAndAMissingFileIsEmpty() {
        val store = store()
        assertNull(store.phrases.value)
        assertEquals(emptyList<Phrase>(), store.load())
        assertEquals(emptyList<Phrase>(), store.phrases.value)
        assertFalse(file.exists())
    }

    @Test fun savesEveryFieldTidiedAndKeepsThemAcrossInstances() {
        val saved = store().save(meVaBien)!!
        assertEquals("me va bien", saved.phrase)
        assertEquals("es bueno para mí", saved.replaced)
        assertEquals("Sounds natural for plans that suit you.", saved.meaning)
        assertEquals("es", saved.language)
        assertEquals(PhraseSource.Rewording, saved.source)
        assertEquals("com.whatsapp", saved.app)
        assertEquals(1_000L, saved.createdAt)

        assertEquals(listOf(saved), store().load())
        assertFalse(File(folder.root, PhrasebookStore.FILE_NAME + ".tmp").exists())
    }

    @Test fun newestFirst() {
        val store = store()
        val first = store.save(PhraseDraft("bon dia", language = "ca"))!!
        val second = store.save(PhraseDraft("fins ara", language = "ca"))!!
        assertEquals(listOf(second, first), store.load())
        assertEquals(listOf(second, first), store().load())
    }

    @Test fun theSamePhraseIsSavedOnceAndGainsAMissingMeaning() {
        val store = store()
        val bare = store.save(meVaBien.copy(meaning = null))!!
        assertNull(bare.meaning)
        val again = store.save(meVaBien.copy(phrase = "Me va bien"))!!
        assertEquals(bare.id, again.id)
        assertEquals("Sounds natural for plans that suit you.", again.meaning)
        assertEquals(1, store.load().size)
        // The same words in another language, or replacing other words, are another phrase.
        store.save(meVaBien.copy(language = "ca"))
        store.save(meVaBien.copy(replaced = "es bien para mí"))
        assertEquals(3, store.load().size)
    }

    @Test fun emptyOrOverlongDraftsAreNotSaved() {
        val store = store()
        assertNull(store.save(PhraseDraft("   ")))
        assertNull(store.save(PhraseDraft("a".repeat(PhrasebookStore.MAX_PHRASE + 1))))
        assertTrue(store.load().isEmpty())
        assertNotNull(store.save(PhraseDraft("a".repeat(PhrasebookStore.MAX_PHRASE))))
    }

    @Test fun blankOptionalFieldsAreStoredAsMissing() {
        val saved = store().save(PhraseDraft("vale", replaced = "  ", meaning = "\n"))!!
        assertNull(saved.replaced)
        assertNull(saved.meaning)
    }

    @Test fun deleteThenRestorePutsThePhraseBackInDateOrder() {
        val store = store()
        val a = store.save(PhraseDraft("a"))!!
        val b = store.save(PhraseDraft("b"))!!
        val c = store.save(PhraseDraft("c"))!!
        assertEquals(b, store.delete(b.id))
        assertEquals(listOf(c, a), store.load())
        assertEquals(listOf(c, a), store().load())
        assertNull(store.delete(b.id))

        store.restore(b)
        store.restore(b)
        assertEquals(listOf(c, b, a), store.load())
        assertEquals(listOf(c, b, a), store().load())
    }

    @Test fun findMatchesWhatSaveWouldDuplicate() {
        val store = store()
        val saved = store.save(meVaBien)!!
        assertSame(saved, store.find(meVaBien.copy(phrase = "ME VA BIEN", meaning = "other")))
        assertNull(store.find(meVaBien.copy(language = "ca")))
    }

    @Test fun anUnreadableFileIsSetAsideNotDeleted() {
        file.writeText("{not json")
        val store = store()
        assertEquals(emptyList<Phrase>(), store.load())
        assertEquals("{not json", File(folder.root, PhrasebookStore.FILE_NAME + ".unreadable").readText())
        store.save(PhraseDraft("hola"))
        assertEquals(1, store().load().size)
    }

    @Test fun unknownFieldsFromANewerBuildStillRead() {
        file.writeText("""{"version":2,"phrases":[{"id":"x","phrase":"vale","createdAt":5,"source":"manual","tags":["a"]}],"extra":1}""")
        assertEquals(listOf(Phrase(id = "x", phrase = "vale", createdAt = 5)), store().load())
    }
}
