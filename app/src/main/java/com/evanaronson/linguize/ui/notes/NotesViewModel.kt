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
 * Notes: the list of notes, and the one open on a note's page (see docs/lab/notes.md). Each
 * note is kept as a draft in the store (`data/write`, `lab_write.json`, names kept so nothing
 * moves). [open] puts a note in the editor: one by id (a new note's id, from [newNoteId], until
 * something is written in it), or with null the current one (the last opened, else the newest,
 * else a new note holding the sample on a fresh install). Text is saved [saveMillis] after
 * typing stops and at once on [flush] (leaving the page or the app). A blank note, or the
 * untouched sample, is never kept. [sample] picks it (read off the main thread): a sentence in
 * one of the writer's languages, see [NoteSamples].
 */
class NotesViewModel(
    private val store: WriteDraftStore,
    private val sample: () -> String,
    private val saveMillis: Long = SAVE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    /** The open note's text. */
    var text by mutableStateOf("")
        private set

    /** The open note's language code (kept from the Writing pad's drafts), or null for auto-detect. */
    var language by mutableStateOf<String?>(null)
        private set

    /** Every saved note, newest first: the Notes list. */
    var notes by mutableStateOf<List<WriteDraft>>(emptyList())
        private set

    /** The notes have been read, so an empty [notes] means there are none. */
    var listed by mutableStateOf(false)
        private set

    /** The note just deleted from the list, until Undo or the next change. */
    var deleted by mutableStateOf<WriteDraft?>(null)
        private set

    /** The note asked for by [open] is still being read; edits wait for it. */
    var loading by mutableStateOf(false)
        private set

    /** The id of the note in the editor; null before [open]. */
    val openId: String? get() = id

    /** The editor holds the untouched sample a fresh install starts with, which is never saved. */
    val showingSample: Boolean get() = sampleText != null && text == sampleText

    private var sampleText: String? = null

    private val lock = Mutex()
    private var id: String? = null
    private var title = ""

    /** A note is in the editor (it may be new, and not saved yet). */
    private var opened = false

    /** The open note is in the store, so every change to it is kept (even back to the sample's text). */
    private var saved = false
    private var saving: Job? = null

    private val ready: Job = viewModelScope.launch {
        val stored = stored { it.load() }
        sampleText = withContext(io) { sample() }
        notes = stored.drafts
        listed = true
    }

    /** An id for a new note: open it with [open]; nothing is kept until something is written. */
    fun newNoteId(): String = newId()

    /**
     * Puts note [noteId] in the editor, or with null the current note. The open note is saved
     * first. Returns false when that note is already open: nothing changes, so a card on it
     * stays. [onResolved] gets the open note's id once it's read, or null when it isn't saved
     * (a new note, or the sample).
     */
    fun open(noteId: String?, onResolved: (String?) -> Unit = {}): Boolean {
        if (opened && (noteId == null || noteId == id)) {
            if (!loading) onResolved(id.takeIf { saved })
            return false
        }
        saving?.cancel()
        val snapshot = if (opened) snapshot() else null
        opened = true
        saved = false
        loading = true
        id = noteId
        title = ""
        text = ""
        deleted = null
        viewModelScope.launch {
            ready.join()
            snapshot?.let { persist(it) }
            val stored = stored { it.load() }
            val draft = if (noteId != null) stored.drafts.firstOrNull { it.id == noteId }
            else stored.current ?: stored.drafts.firstOrNull()
            if (draft != null) {
                show(draft)
                saved = true
            } else {
                id = noteId ?: newId()
                language = null
                // A fresh install starts on the sample, which isn't kept until it's changed.
                if (noteId == null && stored.drafts.isEmpty()) text = sampleText.orEmpty()
            }
            loading = false
            notes = stored { it.setCurrent(draft?.id); it.load().drafts }
            onResolved(draft?.id)
        }
        return true
    }

    fun edit(value: String) {
        if (value == text || loading || !opened) return
        text = value
        scheduleSave(saveMillis)
    }

    fun selectLanguage(code: String?) {
        if (code == language || loading || !opened) return
        language = code
        scheduleSave(0)
    }

    /** Saves the open note now. */
    fun flush() {
        if (!opened || loading) return
        saving?.cancel()
        saving = null
        val snapshot = snapshot()
        viewModelScope.launch { persist(snapshot) }
    }

    /** Deletes note [noteId] from the list; [undoDelete] puts it back. */
    fun delete(noteId: String) {
        val draft = notes.firstOrNull { it.id == noteId } ?: return
        deleted = draft
        notes = notes - draft
        if (noteId == id) {
            // No longer the open note: nothing saves it again, and opening it reads it afresh.
            saving?.cancel()
            opened = false
            saved = false
            id = null
            text = ""
        }
        viewModelScope.launch { stored { it.delete(noteId) } }
    }

    fun undoDelete() {
        val draft = deleted ?: return
        deleted = null
        viewModelScope.launch { notes = stored { it.restore(draft); it.load().drafts } }
    }

    private fun show(draft: WriteDraft) {
        id = draft.id
        title = draft.title
        text = draft.text
        language = draft.language
    }

    private class Snapshot(val id: String?, val text: String, val language: String?, val title: String, val keep: Boolean)

    private fun snapshot() = Snapshot(id, text, language, title, keep = saved || keeps(text))

    private fun keeps(value: String) = value.isNotBlank() && value != sampleText

    private suspend fun persist(snapshot: Snapshot) {
        val noteId = snapshot.id ?: return
        if (snapshot.text.isBlank()) {
            // Emptied: nothing to keep. The note keeps its id, so writing again saves it in place.
            if (id == noteId) saved = false
            notes = stored { it.delete(noteId); it.load().drafts }
            return
        }
        if (!snapshot.keep) return
        val draft = WriteDraft(noteId, snapshot.text, snapshot.language, snapshot.title, clock())
        notes = stored { it.saveCurrent(draft); it.load().drafts }
        if (id == noteId) saved = true
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
