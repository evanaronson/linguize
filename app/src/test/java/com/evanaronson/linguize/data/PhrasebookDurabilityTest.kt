package com.evanaronson.linguize.data

import android.app.Application
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhrasebookDurabilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedPhraseSaveCanBeRetriedWithoutPublishingOrLosingThePreviousPhrase() {
        val file = File(folder.root, "phrases.json")
        val store = PhrasebookStore(file)
        val first = store.save(PhraseDraft("bon dia"))!!
        val blocked = blockTemp(file)
        assertThrows(IOException::class.java) { store.save(PhraseDraft("fins ara")) }
        assertEquals(listOf(first), store.load())
        assertEquals(listOf(first), PhrasebookStore(file).load())

        assertTrue(blocked.deleteRecursively())
        val second = store.save(PhraseDraft("fins ara"))!!
        assertEquals(listOf(second, first), store.phrases.value)
        assertEquals(listOf(second, first), PhrasebookStore(file).load())
    }

    @Test fun failedPhraseReadDoesNotBecomeACachedEmptyStoreOrPermitOverwrite() {
        val file = File(folder.root, "phrases.json")
        val saved = PhrasebookStore(file).save(PhraseDraft("bon dia"))!!
        val original = File(folder.root, "original")
        assertTrue(file.renameTo(original))
        assertTrue(file.mkdir())
        File(file, "keep").writeText("storage obstruction")
        val store = PhrasebookStore(file)

        assertThrows(IOException::class.java) { store.load() }
        assertThrows(IOException::class.java) { store.save(PhraseDraft("new phrase")) }
        assertNull(store.phrases.value)
        assertEquals("storage obstruction", File(file, "keep").readText())

        assertTrue(file.deleteRecursively())
        assertTrue(original.renameTo(file))
        assertEquals(listOf(saved), store.load())
    }

    @Test fun failedQuarantinePreservesBothTheUnreadableFileAndItsEarlierBackup() {
        val file = File(folder.root, "phrases.json")
        file.writeText("{bad json")
        val backup = File(folder.root, "phrases.json.unreadable")
        backup.writeText("earlier backup")
        val store = PhrasebookStore(file)

        assertThrows(IOException::class.java) { store.save(PhraseDraft("new phrase")) }
        assertNull(store.phrases.value)
        assertEquals("{bad json", file.readText())
        assertEquals("earlier backup", backup.readText())
    }

    private fun blockTemp(file: File) = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
    }
}
