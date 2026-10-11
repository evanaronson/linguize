package com.evanaronson.linguize.ui

import com.evanaronson.linguize.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
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

    /** The file for a new export made on [date], with every earlier export removed. */
    fun create(date: LocalDate): File {
        clear()
        folder.mkdirs()
        return File(folder, "linguize-history-$date.jsonl")
    }

    /** Removes every export, finished or not. */
    fun clear() {
        folder.listFiles()?.forEach { it.delete() }
    }

    /** Removes exports written more than [KEEP_MILLIS] ago; whatever they were shared with has had its time. */
    fun prune() {
        val cutoff = clock() - KEEP_MILLIS
        folder.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        /** The cache folder res/xml/file_paths.xml shares. */
        const val FOLDER = "exports"

        /** How long an export is left for the app it was shared with to read: a few minutes. */
        const val KEEP_MILLIS = 5 * 60_000L
    }
}

/**
 * Removes every history export, off the main thread and outliving the screen: once
 * history is cleared or a check deleted, no copy of it is left in an export.
 */
fun App.forgetExports() {
    appScope.launch(Dispatchers.IO) { Exports(cacheDir).clear() }
}
