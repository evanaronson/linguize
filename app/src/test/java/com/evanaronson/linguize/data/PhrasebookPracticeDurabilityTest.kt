package com.evanaronson.linguize.data

import android.app.Application
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.Schedule
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import com.evanaronson.linguize.data.practice.PracticeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PhrasebookPracticeDurabilityTest {
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

    @Test fun unreadablePracticeProgressCannotBeOverwrittenByAnUpdate() = runBlocking {
        val file = File(folder.root, "practice.json")
        file.writeText("{bad json")
        val store = PracticeStore(file)
        var changed = false
        assertThrows(IOException::class.java) { runBlocking { store.read() } }
        assertThrows(IOException::class.java) {
            runBlocking { store.update { changed = true; it.copy(newToday = 1) } }
        }
        assertFalse(changed)
        assertEquals("{bad json", file.readText())
    }

    @Test fun failedPracticeGradeLeavesThePreviousScheduleAndRetriesOnce() = runBlocking {
        val file = File(folder.root, "practice.json")
        val store = PracticeStore(file)
        val initial = Schedule.review(null, Grade.Good, 1_000)
        val before = store.update { it.copy(states = mapOf("item" to initial)) }
        val blocked = blockTemp(file)
        assertThrows(IOException::class.java) {
            runBlocking { store.update { it.copy(states = mapOf("item" to Schedule.review(initial, Grade.Good, 2_000))) } }
        }
        assertEquals(before, PracticeStore(file).read())

        assertTrue(blocked.deleteRecursively())
        val after = store.update { it.copy(states = mapOf("item" to Schedule.review(initial, Grade.Good, 2_000))) }
        assertEquals(2, after.states.getValue("item").reviews)
        assertEquals(after, PracticeStore(file).read())
    }

    private fun blockTemp(file: File) = File(file.parentFile, file.name + ".tmp").also {
        assertTrue(it.mkdir())
        File(it, "keep").writeText("storage obstruction")
    }
}
