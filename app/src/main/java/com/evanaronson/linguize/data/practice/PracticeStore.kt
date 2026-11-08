package com.evanaronson.linguize.data.practice

import android.util.Log
import com.evanaronson.linguize.core.practice.PracticeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Practice's scheduling state, in its own file (`lab_practice.json` in the app's files),
 * apart from History and settings. It holds suggestion ids and review times, no text:
 * the items themselves are rebuilt from History each time.
 */
class PracticeStore(private val file: File) {
    /** The saved progress; empty when there is none or it can't be read. */
    suspend fun read(): PracticeProgress = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { if (file.exists()) json.decodeFromString<PracticeProgress>(file.readText()) else null }
                .onFailure { Log.w(TAG, "Couldn't read practice progress", it) }
                .getOrNull() ?: PracticeProgress()
        }
    }

    /** Applies [change] to the saved progress and saves the result, which it returns. */
    suspend fun update(change: (PracticeProgress) -> PracticeProgress): PracticeProgress = withContext(Dispatchers.IO) {
        lock.withLock {
            val before = runCatching { if (file.exists()) json.decodeFromString<PracticeProgress>(file.readText()) else null }
                .getOrNull() ?: PracticeProgress()
            val after = change(before)
            if (after != before) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, file.name + ".tmp")
                    temp.writeText(json.encodeToString(PracticeProgress.serializer(), after))
                    if (!temp.renameTo(file)) {
                        file.delete()
                        check(temp.renameTo(file)) { "rename failed" }
                    }
                }.onFailure { Log.w(TAG, "Couldn't save practice progress", it) }
            }
            after
        }
    }

    companion object {
        const val FILE = "lab_practice.json"
        private const val TAG = "Practice"
        private val json = Json { ignoreUnknownKeys = true }

        /** One writer at a time for the process: each screen has its own view model. */
        private val lock = Mutex()
    }
}
