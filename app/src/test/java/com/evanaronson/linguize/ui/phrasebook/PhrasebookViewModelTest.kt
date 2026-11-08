package com.evanaronson.linguize.ui.phrasebook

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.data.phrasebook.PhraseDraft
import com.evanaronson.linguize.data.phrasebook.PhrasebookStore
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhrasebookViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = 0L
    private fun store() = PhrasebookStore(File(folder.root, PhrasebookStore.FILE_NAME), clock = { now++ })

    private fun <T> withModel(store: PhrasebookStore, block: (PhrasebookViewModel) -> T): T {
        val model = PhrasebookViewModel(store, io = Dispatchers.Unconfined)
        val owner = ViewModelStore().apply { put("phrasebook", model) }
        return try { block(model) } finally { owner.clear() }
    }

    private fun idle() = repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

    @Test fun loadsThenGroupsAndSearches() {
        val store = store().apply {
            save(PhraseDraft("me va bien", "es bueno para mí", language = "es"))
            save(PhraseDraft("està", "esta", language = "ca"))
            save(PhraseDraft("vale", language = "es"))
        }
        withModel(PhrasebookStore(File(folder.root, PhrasebookStore.FILE_NAME))) { model ->
            idle()
            val state = model.state.value!!
            assertEquals(3, state.total)
            assertEquals(listOf("ca", "es"), state.sections.map { it.language })
            assertEquals(listOf("vale", "me va bien"), state.sections[1].phrases.map { it.phrase })

            model.search("BUENO")
            idle()
            assertEquals(listOf("me va bien"), model.state.value!!.sections.flatMap { it.phrases }.map { it.phrase })
            assertEquals(3, model.state.value!!.total)
            assertEquals(1, model.state.value!!.matching)

            // Export ignores the search.
            assertEquals(3, model.allSections().sumOf { it.phrases.size })
        }
        assertEquals(3, store.load().size)
    }

    @Test fun addSavesATypedPhraseAndRejectsAnEmptyOne() {
        withModel(store()) { model ->
            val results = mutableListOf<Boolean>()
            model.add(PhraseDraft("  ")) { results += it }
            model.add(PhraseDraft("fins ara", meaning = "See you soon", language = "ca")) { results += it }
            idle()
            assertEquals(listOf(false, true), results)
            assertEquals(listOf("fins ara"), model.state.value!!.sections.single().phrases.map { it.phrase })
        }
    }

    @Test fun deleteCanBeUndoneOnceAndConfirmingForgetsIt() {
        val store = store()
        val kept = store.save(PhraseDraft("bon dia", language = "ca"))!!
        val gone = store.save(PhraseDraft("fins ara", language = "ca"))!!
        withModel(store) { model ->
            idle()
            model.delete(gone.id)
            idle()
            assertEquals(gone, model.lastDeleted.value)
            assertEquals(listOf(kept), store.load())
            assertEquals(1, model.state.value!!.total)

            model.undoDelete()
            idle()
            assertNull(model.lastDeleted.value)
            assertEquals(listOf(gone, kept), store.load())
            assertEquals(2, model.state.value!!.total)
            model.undoDelete() // Nothing left to undo.
            assertEquals(2, store.load().size)

            model.delete(kept.id)
            idle()
            model.deleteConfirmed(kept)
            assertNull(model.lastDeleted.value)
            model.undoDelete()
            idle()
            assertEquals(listOf(gone), store.load())
        }
    }
}
