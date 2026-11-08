package com.evanaronson.linguize.ui.languagebridge

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BridgeViewModelTest {
    private fun ok(text: String) = Bridged(Bridged.Status.Ok, "Spanish", text, emptyList())

    @Test fun opensWithTrimmedTextAndLoadsProfileLanguages() {
        val asked = mutableListOf<Triple<String, String, String?>>()
        val vm = BridgeViewModel(languages = { listOf("ca", "es") }) { text, target, hint ->
            asked += Triple(text, target, hint)
            ok("Et trobo a faltar")
        }
        val store = ViewModelStore().apply { put("bridge", vm) }
        try {
            waitFor { vm.targets.isNotEmpty() }
            assertEquals(listOf("ca", "es"), vm.targets)
            vm.open("   ", "ca")
            assertNull(vm.state)
            vm.open("  Te echo de menos ", "ca", sourceHint = "es")
            assertTrue(vm.state != null)
            waitFor { vm.state is BridgeState.Done }
            assertEquals(BridgeState.Done("Te echo de menos", "ca", ok("Et trobo a faltar")), vm.state)
            assertEquals(listOf(Triple("Te echo de menos", "ca", "es")), asked)
            vm.close()
            assertNull(vm.state)
        } finally { store.clear() }
    }

    @Test fun switchingLanguageOrClosingDiscardsALateAnswer() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = BridgeViewModel(languages = { listOf("ca", "es", "fr") }) { _, target, _ ->
            calls++
            if (target == "ca") withContext(NonCancellable) { gate.await() }
            ok("in $target")
        }
        val store = ViewModelStore().apply { put("bridge", vm) }
        try {
            vm.open("Hola", "ca")
            waitFor { calls == 1 }
            vm.changeTarget("fr")
            waitFor { vm.state is BridgeState.Done }
            gate.complete(Unit)
            settle()
            assertEquals("fr", vm.state?.target)
            assertEquals("in fr", (vm.state as BridgeState.Done).result.text)
            // The same language again asks nothing new.
            vm.changeTarget("fr")
            assertEquals(2, calls)
            vm.close()
            vm.changeTarget("es")
            assertNull(vm.state)
        } finally { gate.complete(Unit); store.clear() }
    }

    @Test fun aFailureCanBeRetriedWithTheSameTextAndLanguage() {
        var attempts = 0
        val vm = BridgeViewModel(languages = { error("Settings unavailable") }) { text, target, _ ->
            attempts++
            if (attempts == 1) throw CheckFailure(CheckFailure.Reason.NoKey)
            if (attempts == 2) throw IllegalStateException("Unexpected")
            ok("$text/$target")
        }
        val store = ViewModelStore().apply { put("bridge", vm) }
        try {
            vm.open("Hola", "es")
            waitFor { vm.state is BridgeState.Failed }
            assertEquals(CheckFailure.Reason.NoKey, (vm.state as BridgeState.Failed).failure.reason)
            vm.retryIfFailed()
            waitFor { vm.state is BridgeState.Failed && attempts == 2 }
            assertEquals(CheckFailure.Reason.BadResponse, (vm.state as BridgeState.Failed).failure.reason)
            vm.retry()
            waitFor { vm.state is BridgeState.Done }
            assertEquals("Hola/es", (vm.state as BridgeState.Done).result.text)
            vm.retryIfFailed()
            settle()
            assertEquals(3, attempts)
            assertEquals(emptyList<String>(), vm.targets)
        } finally { store.clear() }
    }

    @Test fun theDefaultTargetFollowsThePinnedOrGuessedLanguage() {
        assertEquals("es", defaultBridgeTarget("Hola", "ca", listOf("ca", "es")))
        assertEquals("ca", defaultBridgeTarget("¿Te acuestas tarde? Mañana no puedo", null, listOf("ca", "es")))
        assertEquals("es", defaultBridgeTarget("Avui no puc, però demà sí", null, emptyList()))
        assertEquals("ca", defaultBridgeTarget("Hola", null, listOf("ca", "es")))
    }

    private fun settle() = repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > until) error("Timed out")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
