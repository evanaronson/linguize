package com.evanaronson.linguize.ui.history

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwipeToDeleteTest {
    @Test
    fun undoRestoresTheSameRowWithoutRepeatingItsDismissal() = runBlocking(FrameClock) {
        withTimeout(5_000) {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(EmptyApplier(), recomposer)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            var visible by mutableStateOf(true)
            var deletes = 0
            val deleted = CompletableDeferred<Unit>()
            lateinit var swipe: SwipeToDismissBoxState

            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                // A launched deletion can change visibility after the first composition.
                yield()
                Snapshot.sendApplyNotifications()
                recomposer.awaitIdle()
            }

            try {
                composition.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        val saved = rememberSaveableStateHolder()
                        if (visible) saved.SaveableStateProvider("same-row") {
                            swipe = rememberDeleteSwipeState {
                                deletes++
                                visible = false
                                deleted.complete(Unit)
                            }
                        }
                    }
                }
                val original = swipe
                swipe.snapTo(SwipeToDismissBoxValue.EndToStart)
                Snapshot.sendApplyNotifications()
                deleted.await()
                settle()

                // LazyColumn keeps a keyed row's saveable state just as this holder does.
                visible = true
                settle()

                assertNotSame(original, swipe)
                assertTrue(visible)
                assertEquals(SwipeToDismissBoxValue.Settled, swipe.currentValue)
                assertEquals(1, deletes)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }
        }
    }

    private object FrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
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
