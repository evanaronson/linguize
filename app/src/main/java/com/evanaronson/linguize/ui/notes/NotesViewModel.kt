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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Production commands outlive the page; tests can give them a bounded scope. */
    private val commandScope: CoroutineScope? = null,
) : ViewModel() {
    /** A requested editor owns its input even before its saved contents have been read. */
    private class Editor(
        val requestedId: String?, var previous: Editor?, var deletionVersion: Int,
        val selection: Long,
    ) {
        var draft by mutableStateOf(Draft(id = requestedId))
        var loading by mutableStateOf(true)
        var resolved = false
        var savedRevision = 0L
        var textEdited = false
        var languageEdited = false
        val callbacks = mutableListOf<(String?) -> Unit>()
    }

    private data class Draft(
        val id: String?, val text: String = "", val language: String? = null,
        val title: String = "", val saved: Boolean = false, val revision: Long = 0,
    )

    private var editor by mutableStateOf<Editor?>(null)
    val text: String get() = editor?.draft?.text.orEmpty()
    val language: String? get() = editor?.draft?.language
    val openId: String? get() = editor?.draft?.id
    /** A resolved editor with an acknowledged row can replace the route's current-note placeholder. */
    val savedId: String? get() = editor?.takeIf { it.resolved && !it.loading }?.draft?.takeIf { it.saved }?.id
    val loading: Boolean get() = editor?.loading == true
    val showingSample: Boolean get() = sampleText != null && text == sampleText

    /** Only acknowledged storage contents are published in the list. */
    var notes by mutableStateOf<List<WriteDraft>>(emptyList())
        private set
    var listed by mutableStateOf(false)
        private set
    private data class DeletedNote(val draft: WriteDraft, val version: Int)
    private var undo by mutableStateOf<DeletedNote?>(null)
    val deleted: WriteDraft? get() = undo?.draft
    private class FailedStorage(val operation: NotesStorageFailure, val noteId: String?, val retry: () -> Unit)
    private var failedStorage by mutableStateOf<FailedStorage?>(null)
    val storageFailure get() = failedStorage?.operation

    private var sampleText: String? = null
    private val failedRestores = linkedMapOf<String, DeletedNote>()
    private val deleting = mutableSetOf<String>()
    private var saving: Job? = null
    private var disposed = false

    // Commands run on Main in request order, including their IO and acknowledgement. Typing
    // stays immediate. A debounce can be cancelled; an enqueued write is never cancelled by it.
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        (commandScope ?: viewModelScope).launch(Dispatchers.Main.immediate) {
            for (command in commands) command()
        }
        reloadNotes()
    }

    private fun enqueue(command: suspend () -> Unit) { commands.trySend(command) }

    private fun reloadNotes(): Unit = enqueue {
        try {
            readNotes()
        } catch (_: IOException) {
            failed(NotesStorageFailure.Read, null, ::reloadNotes)
        }
    }

    private suspend fun readNotes() {
        val loaded = stored { it.load() }
        sampleText = withContext(io) { sample() }
        publish(loaded.drafts)
        if (!disposed) listed = true
        recovered(NotesStorageFailure.Read, null)
    }

    fun newNoteId(): String = newId()

    /**
     * Selects an editor immediately; input during its read belongs to that editor. Earlier
     * requests still save their outgoing input, but only this selection can resolve navigation.
     * Opening the same editor keeps its check card and waits for the same pending resolution.
     */
    fun open(noteId: String?, onResolved: (String?) -> Unit = {}): Boolean {
        if (disposed) return false
        val current = editor
        if (current != null && (noteId == null || noteId == current.draft.id)) {
            if (current.resolved && !current.loading && current.callbacks.isEmpty()) {
                onResolved(current.draft.id.takeIf { current.draft.saved })
            }
            else current.callbacks += onResolved
            return false
        }
        recovered(NotesStorageFailure.Open, failedStorage?.noteId)
        saving?.cancel()
        val next = Editor(noteId, current, deletionVersion(noteId), store.claimSelection())
        next.callbacks += onResolved
        editor = next
        undo = null
        enqueue { openEditor(next) }
        return true
    }

    private suspend fun openEditor(next: Editor): Boolean {
        try {
            resolve(next)
            if (!valid(next)) throw IOException("The note was deleted while opening")
            while (next.textEdited || next.languageEdited) {
                val revision = next.draft.revision
                if (persist(next)) break
                // Typing can supersede this editor's snapshot while IO is queued. Save that
                // newer input before resolving; it is not a storage failure.
                if (next.draft.revision == revision) throw IOException("Newer note contents superseded this editor")
            }
            val selected = next.draft.id.takeIf { next.draft.saved }
            val selection = next.selection
            publish(stored { it.setCurrent(selected, selection); it.load().drafts })
            next.loading = false
            val callbacks = next.callbacks.toList()
            next.callbacks.clear()
            if (!disposed && editor === next) {
                recovered(NotesStorageFailure.Open, next.requestedId)
                callbacks.forEach { if (!disposed && editor === next) it(selected) }
            }
            next.previous = null
            return true
        } catch (_: IOException) {
            if (disposed || editor !== next) return false
            next.loading = false
            // Keep target-associated typing. Without it, show the outgoing editor again so
            // failed saves remain editable; the request retains that editor for its retry.
            editor = recoverable(next)
            editor?.loading = false
            failed(NotesStorageFailure.Open, next.requestedId) {
                saving?.cancel()
                renew(next)
                next.loading = true
                editor = next
                enqueue { openEditor(next) }
            }
            return false
        }
    }

    /** Resolves metadata before a session can be saved, never an empty loading placeholder. */
    private suspend fun resolve(target: Editor) {
        if (!valid(target)) return
        if (!listed) readNotes()
        target.previous?.let { previous ->
            resolve(previous)
            if (persist(previous)) recovered(NotesStorageFailure.Save, previous.draft.id)
        }
        if (target.resolved) return
        val loaded = stored { it.loadSelection(target.requestedId) }
        val draft = loaded.draft
        val buffered = target.draft
        target.draft = Draft(
            id = draft?.id ?: target.requestedId ?: newId(),
            text = if (target.textEdited) buffered.text else draft?.text
                ?: if (target.requestedId == null && loaded.empty) sampleText.orEmpty() else "",
            language = if (target.languageEdited) buffered.language else draft?.language,
            title = draft?.title.orEmpty(),
            saved = draft != null,
            revision = buffered.revision,
        )
        if (target.requestedId == null) target.deletionVersion = loaded.deletionVersion
        target.resolved = true
    }

    private fun recoverable(target: Editor): Editor? {
        if (target.textEdited || target.languageEdited) return target
        var previous = target.previous
        while (previous != null) {
            if (valid(previous) && (previous.resolved || previous.textEdited || previous.languageEdited)) return previous
            previous = previous.previous
        }
        return null
    }

    fun edit(value: String) {
        val current = editor ?: return
        if (value == current.draft.text) return
        renewDeletedEditor(current)
        current.draft = current.draft.copy(text = value, revision = store.noteEdited(current.draft.id))
        current.textEdited = true
        scheduleSave(current, saveMillis)
    }

    fun selectLanguage(code: String?) {
        val current = editor ?: return
        if (code == current.draft.language) return
        renewDeletedEditor(current)
        current.draft = current.draft.copy(language = code, revision = store.noteEdited(current.draft.id))
        current.languageEdited = true
        scheduleSave(current, 0)
    }

    fun flush() {
        val current = editor ?: return
        saving?.cancel()
        saving = null
        enqueue { save(current) }
    }

    /** A deletion and its Undo are published only after the store acknowledges the write. */
    fun delete(noteId: String) {
        if (disposed || notes.none { it.id == noteId } || !deleting.add(noteId)) return
        if (noteId == openId) saving?.cancel()
        enqueue {
            try {
                val (removed, remaining) = stored {
                    val draft = it.load().drafts.firstOrNull { draft -> draft.id == noteId }
                    val version = it.delete(noteId)
                    draft?.let { DeletedNote(it, version) } to it.load().drafts
                }
                publish(remaining)
                if (!disposed) {
                    undo = removed
                    if (editor?.draft?.id == noteId) editor = null
                }
                recovered(NotesStorageFailure.Delete, noteId)
                recovered(NotesStorageFailure.Save, noteId)
            } catch (_: IOException) {
                failed(NotesStorageFailure.Delete, noteId) { delete(noteId) }
            } finally {
                deleting.remove(noteId)
            }
        }
    }

    fun undoDelete() { undo?.let(::restoreNote) }

    private fun restoreNote(removed: DeletedNote): Unit = enqueue {
        val draft = removed.draft
        try {
            publish(stored { it.restore(draft, removed.version); it.load().drafts })
            failedRestores.remove(draft.id)
            if (!disposed && undo == removed) undo = null
            recovered(NotesStorageFailure.Restore, draft.id)
        } catch (_: IOException) {
            failedRestores[draft.id] = removed
            failed(NotesStorageFailure.Restore, draft.id) { restoreNote(removed) }
        }
    }

    private fun deletionVersion(noteId: String?): Int = store.deletionVersion(noteId)
    private fun valid(target: Editor) = target.deletionVersion == deletionVersion(target.draft.id)

    private suspend fun persist(target: Editor): Boolean {
        if (!target.resolved || !valid(target)) return false
        // The command owns this immutable snapshot. Later keystrokes update the session and
        // enqueue another save; they never change the write already running on IO.
        val snapshot = target.draft
        if (snapshot.revision == target.savedRevision) return true
        val noteId = snapshot.id ?: return false
        val expectedDeletion = target.deletionVersion
        val selectedBy = target.selection
        if (!snapshot.saved && snapshot.text.isNotBlank() && snapshot.text == sampleText) {
            target.savedRevision = snapshot.revision
            return true
        }
        val draft = WriteDraft(noteId, snapshot.text, snapshot.language, snapshot.title, clock())
        val (saved, loaded) = stored {
            it.save(draft, selectedBy, snapshot.revision, expectedDeletion) to it.load().drafts
        }
        publish(loaded)
        if (saved) {
            target.savedRevision = snapshot.revision
            target.draft = target.draft.copy(saved = snapshot.text.isNotBlank())
        }
        return saved
    }

    private suspend fun save(target: Editor) {
        if ((!target.resolved || target.previous != null || target.loading || target.callbacks.isNotEmpty()) && !openEditor(target)) return
        try {
            val revision = target.draft.revision
            if (persist(target)) recovered(NotesStorageFailure.Save, target.draft.id)
            else if (!disposed && editor === target && target.draft.revision == revision) {
                throw IOException("Newer note contents superseded this save")
            }
        } catch (_: IOException) {
            failed(NotesStorageFailure.Save, target.draft.id) {
                renew(target)
                enqueue { save(target) }
            }
        }
    }

    /** Fresh typing or an explicit Retry may recreate a note deleted in another host. */
    private fun renewDeletedEditor(target: Editor) {
        if (!valid(target)) {
            target.deletionVersion = deletionVersion(target.draft.id)
            target.draft = target.draft.copy(saved = false)
        }
    }

    private fun renew(target: Editor) {
        if (!valid(target) && !target.textEdited && !target.languageEdited) target.resolved = false
        renewDeletedEditor(target)
        if (target.textEdited || target.languageEdited) {
            target.draft = target.draft.copy(revision = store.noteEdited(target.draft.id))
        }
    }

    private fun publish(loaded: List<WriteDraft>) { if (!disposed) notes = loaded }

    fun retryStorage() { failedStorage?.retry?.invoke() }

    private fun failed(operation: NotesStorageFailure, noteId: String?, retry: () -> Unit) {
        if (disposed) return
        failedStorage = FailedStorage(operation, noteId, retry)
    }

    private fun recovered(operation: NotesStorageFailure, noteId: String?) {
        if (disposed) return
        if (failedStorage?.operation != operation || failedStorage?.noteId != noteId) return
        failedStorage = null
        failedRestores.values.firstOrNull()?.let { removed ->
            failed(NotesStorageFailure.Restore, removed.draft.id) { restoreNote(removed) }
        }
    }

    private fun scheduleSave(target: Editor, after: Long) {
        saving?.cancel()
        saving = viewModelScope.launch {
            if (after > 0) delay(after)
            enqueue { save(target) }
        }
    }

    private suspend fun <T> stored(block: (WriteDraftStore) -> T): T = withContext(io) { block(store) }

    override fun onCleared() {
        disposed = true
        editor = null
        commands.close() // Accepted writes drain on the application scope, then the worker exits.
        super.onCleared()
    }

    companion object {
        const val SAVE_MILLIS = 400L

        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NotesViewModel(
                    store = app.notesStore,
                    sample = { NoteSamples.forLanguages(runCatching { app.settings.myLanguages.codes }.getOrDefault(emptyList())) },
                    commandScope = app.appScope,
                )
            }
        }
    }
}
