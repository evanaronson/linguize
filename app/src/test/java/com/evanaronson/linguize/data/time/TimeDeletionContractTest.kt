package com.evanaronson.linguize.data.time

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimeDeletionContractTest {
    @get:Rule val main = MainDispatcherRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val name = "time-deletion-contracts"
    private val store = TimeStore(context, scope, name, main.dispatcher)

    @After fun close() { scope.cancel(); store.close(); context.deleteDatabase(name) }

    private fun record(id: String) = TimeRecord(id, "chat", "es", 100, 100, 2, 0, 0, 10)

    @Test fun deletedSessionsRejectQueuedAndLaterTimingWrites() = runTest(main.dispatcher) {
        assertTrue(store.save(record("other")))
        store.record(record("deleted")) // Queued, but not yet run.
        assertTrue(store.deleteAcknowledged(listOf("deleted")))
        runCurrent()

        assertEquals(listOf("other"), store.all().map { it.sessionId })
        assertFalse(store.save(record("deleted")))
        assertTrue(store.deleteAcknowledged(listOf("deleted"))) // Already absent is success.
    }

    @Test fun anUnavailableTableIsFailureRatherThanZeroDeletedRows() = runTest(main.dispatcher) {
        assertTrue(store.save(record("session")))
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE messages")
        }

        assertFalse(store.deleteAcknowledged(listOf("session")))
    }
}
