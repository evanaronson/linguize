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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class NotesStorageFailure { Read, Open, Save, Delete, Restore }

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

    /** A storage operation that can be retried without discarding the note or its Undo. */
    var storageFailure by mutableStateOf<NotesStorageFailure?>(null)
        private set

    private var failedNote: String? = null
    private var retryStorageAction: (() -> Unit)? = null
    private val failedRestores = linkedMapOf<String, WriteDraft>()

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
    private val deleting = mutableMapOf<String, CompletableDeferred<Unit>>()
    /** Only successful deletions invalidate snapshots already waiting to save that note. */
    private val deletionVersions = mutableMapOf<String, Int>()

    private val ready: Job = reloadNotes()

    private fun reloadNotes(): Job = viewModelScope.launch {
        try {
            readNotes()
        } catch (_: IOException) {
            failed(NotesStorageFailure.Read, null) { reloadNotes() }
        }
    }

    private suspend fun readNotes() {
        val stored = stored { it.load() }
        sampleText = withContext(io) { sample() }
        notes = stored.drafts
        listed = true
        recovered(NotesStorageFailure.Read, null)
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
        recovered(NotesStorageFailure.Open, failedNote)
        if (opened && (noteId == null || noteId == id)) {
            if (!loading) onResolved(id.takeIf { saved })
            return false
        }
        saving?.cancel()
        val snapshot = if (opened) snapshot() else null
        val wasSaved = saved
        opened = true
        saved = false
        loading = true
        id = noteId
        title = ""
        text = ""
        deleted = null
        viewModelScope.launch {
            try {
                ready.join()
                if (!listed) readNotes()
                snapshot?.let { persist(it); recovered(NotesStorageFailure.Save, it.id) }
                val stored = stored { it.load() }
                val draft = if (noteId != null) stored.drafts.firstOrNull { it.id == noteId }
                else stored.current ?: stored.drafts.firstOrNull()
                // Commit the selection before publishing the newly opened editor.
                notes = stored { it.setCurrent(draft?.id); it.load().drafts }
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
                onResolved(draft?.id)
            } catch (_: IOException) {
                // Opening cleared the editor while IO ran. Restore the outgoing text when its
                // save or the next read fails, so Retry starts with the writer's latest edits.
                val recoverable = snapshot?.takeIf { it.deletionVersion == deletionVersion(it.id) }
                id = recoverable?.id
                text = recoverable?.text.orEmpty()
                language = recoverable?.language
                title = recoverable?.title.orEmpty()
                opened = recoverable != null
                saved = wasSaved && opened
                loading = false
                failed(NotesStorageFailure.Open, noteId) {
                    open(noteId, onResolved)
                }
            }
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
        viewModelScope.launch { save(snapshot) }
    }

    /** Deletes note [noteId] from the list; [undoDelete] puts it back. */
    fun delete(noteId: String) {
        val draft = notes.firstOrNull { it.id == noteId } ?: return
        if (noteId in deleting) return
        val pending = CompletableDeferred<Unit>().also { deleting[noteId] = it }
        if (noteId == id) saving?.cancel()
        viewModelScope.launch {
            try {
                val remaining = stored { it.delete(noteId); it.load().drafts }
                deletionVersions[noteId] = deletionVersion(noteId) + 1
                notes = remaining
                deleted = draft
                if (noteId == id) {
                    // Only the acknowledged deletion may clear the editor and offer Undo.
                    opened = false
                    saved = false
                    id = null
                    text = ""
                }
                recovered(NotesStorageFailure.Delete, noteId)
                recovered(NotesStorageFailure.Save, noteId)
            } catch (_: IOException) {
                failed(NotesStorageFailure.Delete, noteId) { delete(noteId) }
            } finally {
                deleting.remove(noteId)
                pending.complete(Unit)
            }
        }
    }

    fun undoDelete() {
        val draft = deleted ?: return
        restoreNote(draft)
    }

    private fun restoreNote(draft: WriteDraft) {
        viewModelScope.launch {
            try {
                notes = stored { it.restore(draft); it.load().drafts }
                failedRestores.remove(draft.id)
                if (deleted?.id == draft.id) deleted = null
                recovered(NotesStorageFailure.Restore, draft.id)
            } catch (_: IOException) {
                // A later deletion may replace the visible Undo. Its failed predecessor must
                // still retain the exact draft needed to retry, without restoring the wrong one.
                failedRestores[draft.id] = draft
                failed(NotesStorageFailure.Restore, draft.id) { restoreNote(draft) }
            }
        }
    }

    private fun show(draft: WriteDraft) {
        id = draft.id
        title = draft.title
        text = draft.text
        language = draft.language
    }

    private class Snapshot(
        val id: String?, val text: String, val language: String?, val title: String,
        val keep: Boolean, val deletionVersion: Int,
    )

    private fun snapshot() = Snapshot(id, text, language, title, keep = saved || keeps(text), deletionVersion = deletionVersion(id))

    private fun deletionVersion(noteId: String?): Int = noteId?.let { deletionVersions[it] } ?: 0

    private fun keeps(value: String) = value.isNotBlank() && value != sampleText

    private suspend fun persist(snapshot: Snapshot) {
        val noteId = snapshot.id ?: return
        deleting[noteId]?.await()
        if (snapshot.deletionVersion != deletionVersion(noteId)) return
        if (snapshot.text.isBlank()) {
            // Emptied: nothing to keep. The note keeps its id, so writing again saves it in place.
            notes = stored { it.delete(noteId); it.load().drafts }
            if (id == noteId) saved = false
            return
        }
        if (!snapshot.keep) return
        val draft = WriteDraft(noteId, snapshot.text, snapshot.language, snapshot.title, clock())
        notes = stored { it.saveCurrent(draft); it.load().drafts }
        if (id == noteId) saved = true
    }

    private suspend fun save(snapshot: Snapshot) {
        try {
            persist(snapshot)
            recovered(NotesStorageFailure.Save, snapshot.id)
        } catch (_: IOException) {
            failed(NotesStorageFailure.Save, snapshot.id) {
                // Edits made after the failed save supersede its snapshot.
                val retry = if (opened && !loading && id == snapshot.id) this@NotesViewModel.snapshot() else snapshot
                viewModelScope.launch { save(retry) }
            }
        }
    }

    fun retryStorage() { retryStorageAction?.invoke() }

    private fun failed(operation: NotesStorageFailure, noteId: String?, retry: () -> Unit) {
        storageFailure = operation
        failedNote = noteId
        retryStorageAction = retry
    }

    private fun recovered(operation: NotesStorageFailure, noteId: String?) {
        if (storageFailure != operation || failedNote != noteId) return
        storageFailure = null
        failedNote = null
        retryStorageAction = null
        failedRestores.values.firstOrNull()?.let { draft ->
            failed(NotesStorageFailure.Restore, draft.id) { restoreNote(draft) }
        }
    }

    private fun scheduleSave(after: Long) {
        saving?.cancel()
        saving = viewModelScope.launch {
            if (after > 0) delay(after)
            save(snapshot())
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
