package com.evanaronson.linguize.ui.history

import android.app.Application
import com.evanaronson.linguize.data.Exports
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionViewModelTest {
    @get:Rule val cache = TemporaryFolder()

    @Test
    fun deletionRetiresAnExportRecreatedWhileTheDatabaseDeleteWasWaiting() = runBlocking {
        withTimeout(5_000) {
            val context = RuntimeEnvironment.getApplication()
            val deletionStarted = CompletableDeferred<Unit>()
            val finishDeletion = CompletableDeferred<Unit>()
            val retired = CompletableDeferred<Unit>()
            val exports = Exports(cache.root)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val history = object : HistoryStore by SqliteHistoryStore(context, "unused") {
                override suspend fun detail(id: String): SessionDetail? = null
                override suspend fun delete(id: String): Boolean {
                    deletionStarted.complete(Unit)
                    finishDeletion.await()
                    return true
                }
            }
            val model = SessionViewModel(history, prompt = { error("No replay needed") },
                AppLabels(context.packageManager), scope, retireExports = {
                    exports.clear()
                    retired.complete(Unit)
                })
            try {
                model.load("deleted-session")
                model.delete()
                deletionStarted.await()
                assertFalse(retired.isCompleted)
                // Another settings host exported while the row still existed in storage.
                val file = exports.write(LocalDate.now()) {
                    it.write("deleted text".toByteArray())
                    true
                }!!
                assertTrue(file.exists())
                finishDeletion.complete(Unit)
                retired.await()
                assertFalse(file.exists())
                assertFalse(exports.isCurrent(file))
            } finally {
                finishDeletion.complete(Unit)
                scope.cancel()
            }
        }
    }
}
