package com.evanaronson.linguize.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.evanaronson.linguize.App
import com.evanaronson.linguize.data.write.WriteDraft
import com.evanaronson.linguize.data.write.WriteDraftStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Notes' editor (once Write): the open note's text and language, and the other notes. Each note is kept as a draft in the store (`data/write`, `lab_write.json`, names kept so nothing moves). Text is saved
 * [saveMillis] after typing stops and at once on [flush] (leaving the tab or the app). A blank
 * draft, or the untouched sample a fresh install starts with, is never kept. [sample] picks it
 * (read off the main thread): a sentence in one of the writer's languages, see [NoteSamples].
 */
class NotesViewModel(
    private val store: WriteDraftStore,
    private val sample: () -> String,
    private val saveMillis: Long = SAVE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    var text by mutableStateOf("")
        private set

    /** A language code, or null for auto-detect. */
    var language by mutableStateOf<String?>(null)
        private set

    /** The other drafts, newest first: the Drafts list. */
    var drafts by mutableStateOf<List<WriteDraft>>(emptyList())
        private set

    /** The draft just deleted from the list, until Undo or the next change. */
    var deleted by mutableStateOf<WriteDraft?>(null)
        private set

    /** The editor holds the untouched sample a fresh install starts with, which is never saved. */
    val showingSample: Boolean get() = sampleText != null && text == sampleText

    private var sampleText: String? = null

    private val lock = Mutex()
    private var id: String? = null
    private var title = ""
    private var touched = false
    private var saving: Job? = null

    init {
        viewModelScope.launch {
            val stored = stored { it.load() }
            sampleText = withContext(io) { sample() }
            // Typing before the drafts were read wins over what was stored.
            if (!touched) {
                val current = stored.current
                show(current)
                if (current == null && stored.drafts.isEmpty()) text = sampleText.orEmpty()
            }
            drafts = stored.others
        }
    }

    fun edit(value: String) {
        if (value == text) return
        text = value
        touched = true
        scheduleSave(saveMillis)
    }

    fun selectLanguage(code: String?) {
        if (code == language) return
        language = code
        touched = true
        scheduleSave(0)
    }

    /** Saves the open draft now. */
    fun flush() {
        saving?.cancel()
        saving = null
        val snapshot = snapshot()
        viewModelScope.launch { persist(snapshot) }
    }

    /** Keeps the open draft in the list and starts a blank one. */
    fun newDraft() {
        saving?.cancel()
        val snapshot = snapshot()
        show(null)
        touched = true
        viewModelScope.launch {
            persist(snapshot)
            drafts = stored { it.setCurrent(null); it.load().others }
        }
    }

    /** Opens draft [draftId] from the list; the open one goes into the list. */
    fun open(draftId: String) {
        saving?.cancel()
        val snapshot = snapshot()
        deleted = null
        viewModelScope.launch {
            persist(snapshot)
            val stored = stored { it.setCurrent(draftId); it.load() }
            stored.current?.let { show(it); touched = true }
            drafts = stored.others
        }
    }

    /** Deletes draft [draftId] from the list; [undoDelete] puts it back. */
    fun delete(draftId: String) {
        val draft = drafts.firstOrNull { it.id == draftId } ?: return
        deleted = draft
        drafts = drafts - draft
        viewModelScope.launch { stored { it.delete(draftId) } }
    }

    fun undoDelete() {
        val draft = deleted ?: return
        deleted = null
        viewModelScope.launch { drafts = stored { it.restore(draft); it.load().others } }
    }

    private fun show(draft: WriteDraft?) {
        id = draft?.id
        title = draft?.title.orEmpty()
        text = draft?.text.orEmpty()
        if (draft != null) language = draft.language
    }

    private class Snapshot(val id: String?, val text: String, val language: String?, val title: String)

    /** What to save of the open draft; a new draft gets its id here, so a later save updates it. */
    private fun snapshot(): Snapshot {
        if (id == null && keeps(text)) id = newId()
        return Snapshot(id, text, language, title)
    }

    private fun keeps(value: String) = value.isNotBlank() && value != sampleText

    private suspend fun persist(snapshot: Snapshot) {
        val draftId = snapshot.id ?: return
        if (snapshot.text.isBlank()) {
            // Emptied: nothing to keep. It gets a new id if writing starts again.
            if (id == draftId) id = null
            stored { it.delete(draftId) }
            return
        }
        stored { it.saveCurrent(WriteDraft(draftId, snapshot.text, snapshot.language, snapshot.title, clock())) }
    }

    private fun scheduleSave(after: Long) {
        saving?.cancel()
        saving = viewModelScope.launch {
            if (after > 0) delay(after)
            persist(snapshot())
        }
    }

    private suspend fun <T> stored(block: (WriteDraftStore) -> T): T = lock.withLock { withContext(io) { block(store) } }

    companion object {
        const val SAVE_MILLIS = 400L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NotesViewModel(
                    store = WriteDraftStore(
                        File(app.filesDir, WriteDraftStore.FILE_NAME),
                        legacy = File(app.filesDir, WriteDraftStore.PAD_FILE_NAME),
                    ),
                    sample = { NoteSamples.forLanguages(runCatching { app.settings.myLanguages.codes }.getOrDefault(emptyList())) },
                )
            }
        }
    }
}
