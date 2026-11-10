package com.evanaronson.linguize.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.LocalDate

/**
 * History and Words exports, written to the cache folder res/xml/file_paths.xml shares so the share
 * sheet can hand them on. History export replaces every older file; Words replaces only
 * earlier Words files. Clear and any data deletion retire both, as does [prune] once a
 * file is [KEEP_MILLIS] old. Each receiving app gets a URI unique to its export.
 * File work, so call it off the main thread.
 */
class Exports(cacheDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val folder = File(cacheDir, FOLDER)
    private val writes = Mutex()
    private val ownership = Any()
    private var retirement = 0L
    private var wordsRequested = 0L
    private var currentHistory: File? = null
    private var currentWords: File? = null

    /** Capture before reading the evidence from which an export will later be made. */
    fun generation(): Long = synchronized(ownership) { retirement }

    class WordsRequest internal constructor(
        internal val owner: Exports,
        internal val generation: Long,
        internal val sequence: Long,
    )

    /** Capture on the export action, before queuing IO; older Words requests then cannot replace it. */
    fun requestWords(expectedGeneration: Long): WordsRequest = synchronized(ownership) {
        // A stale evidence snapshot must not supersede another host's valid pending request.
        val sequence = if (expectedGeneration == retirement) ++wordsRequested else wordsRequested
        WordsRequest(this, expectedGeneration, sequence)
    }

    /** A screen can check retirement without doing file I/O on the main thread. */
    fun isCurrent(file: File): Boolean = synchronized(ownership) { file == currentHistory || file == currentWords }

    /**
     * Writes one complete export. All hosts use the application's instance, so clearing cannot
     * finish while a writer is still recreating its file. A unique name also means a URI
     * granted for an earlier export can never read a later export made on the same date.
     */
    suspend fun write(date: LocalDate, write: suspend (OutputStream) -> Boolean): File? {
        val expected = generation()
        return writeFile("linguize-history-$date-", ".jsonl", words = false,
            currentRequest = { retirement == expected }, write = write)
    }

    /** Words keeps the existing History URI alive and replaces only older Words CSV files. */
    suspend fun writeWords(
        date: LocalDate,
        language: String?,
        request: WordsRequest,
        write: suspend (OutputStream) -> Boolean,
    ): File? = writeFile("$WORDS_PREFIX${language ?: "other"}-$date-", ".csv", words = true,
        currentRequest = {
            request.owner === this && request.generation == retirement && request.sequence == wordsRequested
        }, write = write)

    private suspend fun writeFile(
        prefix: String,
        suffix: String,
        words: Boolean,
        currentRequest: () -> Boolean,
        write: suspend (OutputStream) -> Boolean,
    ): File? = writes.withLock {
        if (!synchronized(ownership) { currentRequest() }) return@withLock null
        clearFiles(wordsOnly = words)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create export folder")
        val file = File.createTempFile(prefix, suffix, folder)
        var complete = false
        try {
            if (!file.outputStream().buffered().use { write(it) }) return@withLock null
            currentCoroutineContext().ensureActive()
            synchronized(ownership) {
                // Clear invalidates immediately, even while it waits for this writer's mutex.
                if (!currentRequest()) return@withLock null
                if (words) currentWords = file else currentHistory = file
                complete = true
            }
            file
        } finally {
            // Failure, cancellation, and superseded requests never leave a partial file to share.
            if (!complete) file.delete()
        }
    }

    /** Removes every export, finished or not. */
    suspend fun clear() {
        clearAcknowledged()
    }

    /** Stops sharing/queued snapshots immediately; the caller still clears files off the main thread. */
    fun invalidate() = synchronized(ownership) {
        retirement++
        currentHistory = null
        currentWords = null
    }

    /** Same retirement policy, but reports failure so durable deletion work can retry it. */
    suspend fun clearAcknowledged(): Boolean {
        invalidate()
        return writes.withLock { clearFiles() }
    }

    /** Retires just this result; an old screen must not delete another host's newer export. */
    suspend fun discard(file: File) {
        writes.withLock {
            if (file.parentFile == folder) {
                retire(file)
                file.delete()
            }
        }
    }

    /** Removes exports written more than [KEEP_MILLIS] ago; whatever they were shared with has had its time. */
    suspend fun prune() {
        writes.withLock {
            val now = clock()
            // A wall clock set backwards must not leave private exports around indefinitely.
            deleteFiles { it.lastModified() < now - KEEP_MILLIS || it.lastModified() > now }
        }
    }

    private fun retire(file: File) = synchronized(ownership) {
        if (currentHistory == file) currentHistory = null
        if (currentWords == file) currentWords = null
    }

    private fun clearFiles(wordsOnly: Boolean = false): Boolean {
        // A writer captured after invalidation may have won the writer mutex first.
        synchronized(ownership) {
            if (!wordsOnly) currentHistory = null
            currentWords = null
        }
        return deleteFiles { !wordsOnly || it.name.startsWith(WORDS_PREFIX) }
    }

    /** Best-effort callers ignore the result; durable deletion work retries an incomplete pass. */
    private fun deleteFiles(matches: (File) -> Boolean): Boolean {
        val files = try { Files.newDirectoryStream(folder.toPath()) }
            catch (_: NoSuchFileException) { return true }
            catch (_: IOException) { return false }
            catch (_: SecurityException) { return false }
        return try {
            files.use {
                var complete = true
                for (path in it) {
                    val file = path.toFile()
                    if (!matches(file)) continue
                    retire(file)
                    try { Files.deleteIfExists(path) }
                    catch (_: IOException) { complete = false }
                }
                complete
            }
        } catch (_: IOException) { false }
        catch (_: DirectoryIteratorException) { false }
        catch (_: SecurityException) { false }
    }

    companion object {
        /** The cache folder res/xml/file_paths.xml shares. */
        const val FOLDER = "exports"
        private const val WORDS_PREFIX = "linguize-words-"

        /** How long an export is left for the app it was shared with to read: a few minutes. */
        const val KEEP_MILLIS = 5 * 60_000L
    }
}

