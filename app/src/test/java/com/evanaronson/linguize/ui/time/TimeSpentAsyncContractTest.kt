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

    private fun model(store: TimeStore, sessions: suspend () -> Set<String>?): TimeSpentViewModel {
        val owner = ViewModelStore().also { owners += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer { TimeSpentViewModel(store, sessions, { now }, main.dispatcher) }
        })[TimeSpentViewModel::class.java]
    }

    @Test fun anOldLanguageReadCannotReplaceTheCurrentReportOrSweepItsMeasurements() = runTest(main.dispatcher) {
        val store = store()
        val oldSessions = CompletableDeferred<Set<String>>()
        var reads = 0
        val model = model(store) {
            if (reads++ == 0) withContext(NonCancellable) { oldSessions.await() } else ids
        }
        model.refresh("es")
        runCurrent()
        model.refresh("ca")
        advanceUntilIdle()
        val catalan = model.state.value.report!!
        assertEquals("ca", model.state.value.language)
        assertEquals(listOf("ca"), catalan.languages.map { it.language })

        // If applied, this obsolete session list would permanently delete both old records.
        oldSessions.complete(emptySet())
        advanceUntilIdle()
        assertSame(catalan, model.state.value.report)
        assertEquals("ca", model.state.value.language)
        assertEquals(ids, store.all().map { it.sessionId }.toSet())
    }

    @Test fun aLanguageChangeClearsThePreviousScopesReportBeforeTheReadStarts() = runTest(main.dispatcher) {
        val store = store()
        val pending = CompletableDeferred<Set<String>>()
        var reads = 0
        val model = model(store) { if (reads++ == 0) ids else pending.await() }
        model.refresh("es")
        advanceUntilIdle()
        assertEquals(listOf("es"), model.state.value.report!!.languages.map { it.language })

        model.refresh("ca")
        assertEquals("ca", model.state.value.language)
        assertNull(model.state.value.report)
        runCurrent()
        assertNull(model.state.value.report)
        pending.complete(ids)
        advanceUntilIdle()
        assertEquals(listOf("ca"), model.state.value.report!!.languages.map { it.language })
    }

    @Test fun aFailedHistoryReadStillPreservesMeasurementsAndTheRequestedScope() = runTest(main.dispatcher) {
        val store = store()
        val model = model(store) { null }
        model.refresh("ca")
        advanceUntilIdle()
        assertEquals("ca", model.state.value.language)
        assertEquals(listOf("ca"), model.state.value.report!!.languages.map { it.language })
        assertEquals(ids, store.all().map { it.sessionId }.toSet())
    }
}
