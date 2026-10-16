package com.evanaronson.linguize.ui.history

import android.content.Context
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.evanaronson.linguize.App
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SqliteHistoryStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class HistoryDeletionFeedbackTest {
    @Test
    fun retryFailureBeforeTheNextFrameShowsAnotherRetryForTheSameIds() {
        val clock = HeldFrameClock()
        runBlocking(clock) {
            withTimeout(5_000) {
                val app = RuntimeEnvironment.getApplication() as App
                val calls = AtomicInteger()
                val succeeds = AtomicBoolean(false)
                val history = object : HistoryStore by SqliteHistoryStore(app, "unused") {
                    override suspend fun delete(id: String): Boolean {
                        calls.incrementAndGet()
                        return succeeds.get()
                    }
                }
                val model = HistoryViewModel(app, history)
                model.delete("check")
                model.commitDelete("check")
                waitUntil { model.failedDeleteGeneration.value == 1L }

                val snackbar = SnackbarHostState()
                val recomposer = Recomposer(coroutineContext)
                val composition = Composition(EmptyApplier(), recomposer)
                val runner = launch { recomposer.runRecomposeAndApplyChanges() }
                try {
                    composition.setContent {
                        CompositionLocalProvider(LocalContext provides app, LocalResources provides app.resources) {
                            HistoryDeletionFeedback(model, snackbar)
                        }
                    }
                    waitUntil { snackbar.currentSnackbarData != null }

                    repeat(2) { attempt ->
                        val previous = requireNotNull(snackbar.currentSnackbarData)
                        previous.performAction()
                        // No Compose frame is allowed until retry has failed and restored
                        // exactly the same set: the transient empty set cannot restart effects.
                        waitUntil { model.failedDeleteGeneration.value == attempt + 2L }
                        assertEquals(setOf("check"), model.failedDeletes.value)
                        assertNull(snackbar.currentSnackbarData)
                        Snapshot.sendApplyNotifications()
                        yield()
                        clock.frames.send(Unit)
                        waitUntil { snackbar.currentSnackbarData != null }
                        assertNotSame(previous, snackbar.currentSnackbarData)
                    }

                    succeeds.set(true)
                    requireNotNull(snackbar.currentSnackbarData).performAction()
                    waitUntil {
                        calls.get() == 4 && app.getSharedPreferences("recent", Context.MODE_PRIVATE)
                            .all.keys.none { it.startsWith("pendingOperation:") }
                    }
                    assertEquals(emptySet<String>(), model.failedDeletes.value)
                    assertNull(snackbar.currentSnackbarData)
                } finally {
                    composition.dispose()
                    recomposer.cancel()
                    runner.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
            delay(1)
        }
    }

    private class HeldFrameClock : MonotonicFrameClock {
        val frames = Channel<Unit>(Channel.UNLIMITED)
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            frames.receive()
            return onFrame(System.nanoTime())
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
