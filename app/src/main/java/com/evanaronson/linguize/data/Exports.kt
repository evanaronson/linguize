package com.evanaronson.linguize.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

/**
 * History exports, written to the cache folder res/xml/file_paths.xml shares so the share
 * sheet can hand them on. An export holds every kept text, so it stays only as long as
 * the app it went to may still be reading it: the next export, Clear and any delete
 * remove it, and so does [prune] once it's [KEEP_MILLIS] old.
 * File work, so call it off the main thread.
 */
class Exports(cacheDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val folder = File(cacheDir, FOLDER)
    private val writes = Mutex()
    @Volatile private var current: File? = null

    /** A screen can check retirement without doing file I/O on the main thread. */
    fun isCurrent(file: File): Boolean = current == file

    /**
     * Writes one complete export. All hosts use the application's instance, so clearing cannot
     * finish while a writer is still recreating its file. A unique name also means a URI
     * granted for an earlier export can never read a later export made on the same date.
     */
    suspend fun write(date: LocalDate, write: suspend (OutputStream) -> Boolean): File? = writes.withLock {
        clearFiles()
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create export folder")
        val file = File.createTempFile("linguize-history-$date-", ".jsonl", folder)
        var complete = false
        try {
            if (!file.outputStream().buffered().use { write(it) }) return@withLock null
            currentCoroutineContext().ensureActive()
            current = file
            complete = true
            file
        } finally {
            // Failure and cancellation never leave a partial history file to share.
            if (!complete) file.delete()
        }
    }

    /** Removes every export, finished or not. */
    suspend fun clear() = writes.withLock { clearFiles() }

    /** Retires just this result; an old screen must not delete another host's newer export. */
    suspend fun discard(file: File) {
        writes.withLock {
            if (file.parentFile == folder) {
                if (current == file) current = null
                file.delete()
            }
        }
    }

    /** Removes exports written more than [KEEP_MILLIS] ago; whatever they were shared with has had its time. */
    suspend fun prune() = writes.withLock {
        val now = clock()
        // A wall clock set backwards must not leave private exports around indefinitely.
        folder.listFiles()?.filter { it.lastModified() < now - KEEP_MILLIS || it.lastModified() > now }
            ?.forEach {
                if (current == it) current = null
                it.delete()
            }
    }

    private fun clearFiles() {
        current = null
        folder.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** The cache folder res/xml/file_paths.xml shares. */
        const val FOLDER = "exports"

        /** How long an export is left for the app it was shared with to read: a few minutes. */
        const val KEEP_MILLIS = 5 * 60_000L
    }
}

