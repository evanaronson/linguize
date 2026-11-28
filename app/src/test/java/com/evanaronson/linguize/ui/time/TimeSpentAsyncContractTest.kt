package com.evanaronson.linguize.ui.time

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.core.time.TimeSweep
import com.evanaronson.linguize.data.time.TimeStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SessionText
import com.evanaronson.linguize.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimeSpentAsyncContractTest {
    @get:Rule val main = MainDispatcherRule()
    private val owners = mutableListOf<ViewModelStore>()
    private val stores = mutableListOf<TimeStore>()
    private val now = 10 * TimeSweep.GRACE_MS
    private val ids = setOf("es", "ca")
    private val texts = ids.map { SessionText(it, Origin.Menu, it, "Hola, com estàs avui?", 1_000) }

    @After fun close() {
        owners.forEach { it.clear() }
        stores.forEach { it.close() }
    }

    private suspend fun TestScope.store(): TimeStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TimeStore(context, backgroundScope, name = null, dispatcher = main.dispatcher).also { store ->
            stores += store
            ids.forEach { language ->
                store.save(TimeRecord(language, "menu", language, null, 5_000, 7, 2, 1, 1_000))
            }
        }
    }

    private fun model(store: TimeStore, sessions: suspend () -> List<SessionText>?): TimeSpentViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { TimeSpentViewModel(store, sessions, { now }, main.dispatcher) }
        })[TimeSpentViewModel::class.java]
    }

    @Test fun anOldLanguageReadCannotReplaceTheCurrentReportOrSweepItsMeasurements() = runTest(main.dispatcher) {
        val store = store()
        val oldSessions = CompletableDeferred<List<SessionText>>()
        var reads = 0
        val model = model(store) {
            if (reads++ == 0) withContext(NonCancellable) { oldSessions.await() } else texts
        }
        model.refresh("es")
        runCurrent()
        model.refresh("ca")
        advanceUntilIdle()
        val catalan = model.state.value.report!!
        assertEquals("ca", model.state.value.language)
        assertEquals(listOf("ca"), catalan.languages.map { it.language })

        // If applied, this obsolete session list would permanently delete both old records.
        oldSessions.complete(emptyList())
        advanceUntilIdle()
        assertSame(catalan, model.state.value.report)
        assertEquals("ca", model.state.value.language)
        assertEquals(ids, store.all().map { it.sessionId }.toSet())
    }

    @Test fun aLanguageChangeClearsThePreviousScopesReportBeforeTheReadStarts() = runTest(main.dispatcher) {
        val store = store()
        val pending = CompletableDeferred<List<SessionText>>()
        var reads = 0
        val model = model(store) { if (reads++ == 0) texts else pending.await() }
        model.refresh("es")
        advanceUntilIdle()
        assertEquals(listOf("es"), model.state.value.report!!.languages.map { it.language })

        model.refresh("ca")
        assertEquals("ca", model.state.value.language)
        assertNull(model.state.value.report)
        runCurrent()
        assertNull(model.state.value.report)
        pending.complete(texts)
        advanceUntilIdle()
        assertEquals(listOf("ca"), model.state.value.report!!.languages.map { it.language })
    }

    @Test fun olderChecksCountUntilTheyreDeletedFromHistory() = runTest(main.dispatcher) {
        val store = store()
        // Two checks from before Time spent: 4 words each ("Hola, com estàs avui?"), no record.
        val older = listOf("old-1", "old-2").map { SessionText(it, Origin.Menu, "ca", "Hola, com estàs avui?", 500) }
        var history = texts + older
        val model = model(store) { history }
        model.refresh()
        advanceUntilIdle()
        val all = model.state.value.report!!.allTime
        assertEquals(4, all.messages)
        assertEquals(2, all.reviewUntimedMessages)
        assertEquals(10_000L, all.reviewMs)
        // Records: 7 words each; older checks 4 each, all at the 4 s default (no chat writing measured).
        assertEquals((7 + 7 + 4 + 4) * 4_000L, all.estimatedWritingMs)

        history = texts + older.take(1)
        model.refresh()
        advanceUntilIdle()
        assertEquals(1, model.state.value.report!!.allTime.reviewUntimedMessages)
        assertEquals(3, model.state.value.report!!.allTime.messages)

        // A failed History read adds no older checks (and sweeps nothing).
        val failed = model(store) { null }
        failed.refresh()
        advanceUntilIdle()
        assertEquals(0, failed.state.value.report!!.allTime.reviewUntimedMessages)
    }

    @Test fun aFailedHistoryReadStillPreservesMeasurementsAndTheRequestedScope()= runTest(main.dispatcher) {
        val store = store()
        val model = model(store) { null }
        model.refresh("ca")
        advanceUntilIdle()
        assertEquals("ca", model.state.value.language)
        assertEquals(listOf("ca"), model.state.value.report!!.languages.map { it.language })
        assertEquals(ids, store.all().map { it.sessionId }.toSet())
    }
}
