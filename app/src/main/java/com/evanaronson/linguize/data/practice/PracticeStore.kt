package com.evanaronson.linguize.data.practice

import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.data.writeAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Practice's scheduling state, in its own file (`lab_practice.json` in the app's files),
 * apart from History and settings. It holds suggestion ids and review times, no text:
 * the items themselves are rebuilt from History each time.
 */
class PracticeStore(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** The saved progress; only a missing file means empty. Read failures leave it untouched. */
    suspend fun read(): PracticeProgress = withContext(io) {
        lock.withLock { readSaved() }
    }

    /** Applies [change] to the saved progress and saves the result, which it returns. */
    suspend fun update(change: (PracticeProgress) -> PracticeProgress): PracticeProgress = withContext(io) {
        lock.withLock {
            val before = readSaved()
            val after = change(before)
            if (after != before) {
                writeAtomically(file, json.encodeToString(PracticeProgress.serializer(), after))
            }
            after
        }
    }

    private fun readSaved(): PracticeProgress {
        val text = try {
            String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return PracticeProgress()
        }
        return try {
            json.decodeFromString<PracticeProgress>(text)
        } catch (e: IllegalArgumentException) {
            throw IOException("Couldn't read practice progress", e)
        }
    }

    companion object {
        const val FILE = "lab_practice.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** One writer at a time for the process: each screen has its own view model. */
        private val lock = Mutex()
    }
}
