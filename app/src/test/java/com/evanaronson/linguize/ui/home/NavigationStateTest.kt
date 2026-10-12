package com.evanaronson.linguize.ui.home

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavigationStateTest {
    @Test
    fun savingDropsVisitedDetailsAndRestoresOnlyTheLivePath() = runBlocking(FrameClock) {
        withTimeout(5_000) {
            val recomposer = Recomposer(coroutineContext)
            val runner = launch { recomposer.runRecomposeAndApplyChanges() }
            var navigation by mutableStateOf(Navigation())
            lateinit var currentValue: MutableState<Int>

            fun attach(registry: SaveableStateRegistry) = Composition(EmptyApplier(), recomposer).also { composition ->
                composition.setContent {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        val saved = rememberNavigationStateHolder(navigation)
                        saved.SaveableStateProvider(navigation.current.saved) {
                            currentValue = rememberSaveable { mutableStateOf(0) }
                        }
                    }
                }
            }

            suspend fun settle() {
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                // Pruning updates its remembered list in a SideEffect.
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
            }

            val registry = SaveableStateRegistry(restoredValues = null) { true }
            var composition = attach(registry)
            try {
                currentValue.value = 11
                navigation = navigation.open(Screen.History)
                settle()
                currentValue.value = 22

                repeat(50) { index ->
                    navigation = navigation.open(Screen.Detail("visited-$index"))
                    settle()
                    currentValue.value = 100 + index
                    navigation = navigation.back()
                    settle()
                    assertEquals(22, currentValue.value)
                }

                navigation = navigation.open(Screen.Detail("active"))
                settle()
                currentValue.value = 33
                val saved = registry.performSave()
                assertEquals(setOf("home", "history", "detail:active"), screenKeys(saved))

                val savedNavigation = navigation.saved
                composition.dispose()
                navigation = Navigation.restore(savedNavigation)
                val restored = SaveableStateRegistry(restoredValues = saved) { true }
                composition = attach(restored)
                settle()
                assertEquals(33, currentValue.value)

                navigation = navigation.back()
                settle()
                assertEquals(22, currentValue.value)
                assertEquals(setOf("home", "history"), screenKeys(restored.performSave()))

                navigation = navigation.back()
                settle()
                assertEquals(11, currentValue.value)
                assertEquals(setOf("home"), screenKeys(restored.performSave()))
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.cancelAndJoin()
            }
        }
    }

    /** Screen keys inside the root registry's nested holder state; generated keys are opaque. */
    private fun screenKeys(value: Any?): Set<String> = when (value) {
        is Map<*, *> -> value.keys.filterIsInstance<String>().filter { Screen.restore(it) != null }.toSet() +
            value.values.flatMap { screenKeys(it) }
        is List<*> -> value.flatMap { screenKeys(it) }.toSet()
        else -> emptySet()
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
