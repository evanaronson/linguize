package com.evanaronson.linguize.ui.understand

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.UnderstandContext
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.ui.ClipboardText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UnderstandViewModelTest {
    private val initialContext = UnderstandContext(Provider.Gemini, "first-model", "English")

    @Test fun retryUsesTheCapturedTextAndCancellationRejectsLateAnswers() {
        val inputs = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val vm = UnderstandViewModel(context = { initialContext }) { text, _ ->
            inputs += text
            attempts++
            if (attempts == 1) throw CheckFailure(CheckFailure.Reason.Offline)
            if (attempts == 3) withContext(NonCancellable) { gate.await() }
            Understood("Translation", emptyList())
        }
        val store = ViewModelStore().apply { put("understand", vm) }
        try {
            vm.captured(ClipboardText.Text("copied message"))
            waitFor { vm.state is UnderstandState.Failed }
            vm.retry()
            waitFor { vm.state is UnderstandState.Done }
            assertEquals(listOf("copied message", "copied message"), inputs)
            vm.retry()
            waitFor { attempts == 3 }
            vm.readingClipboard()
            gate.complete(Unit)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(UnderstandState.ReadingClipboard, vm.state)
            vm.captured(ClipboardText.NoText)
            assertEquals(UnderstandState.Message(false), vm.state)
            vm.captured(ClipboardText.Unavailable)
            assertEquals(UnderstandState.Message(true), vm.state)
            assertEquals(3, attempts)
        } finally { gate.complete(Unit); store.clear() }
    }

    @Test fun returningFromSettingsRefreshesDoneAndLoadingFromTheCapturedText() {
        var context = initialContext
        val requests = mutableListOf<Pair<String, UnderstandContext>>()
        val oldAnswer = CompletableDeferred<Unit>()
        val vm = UnderstandViewModel(context = { context }) { text, used ->
            requests += text to used
            if (used.model == "slow-model") withContext(NonCancellable) { oldAnswer.await() }
            Understood(used.native + ":" + used.model, emptyList())
        }
        val store = ViewModelStore().apply { put("understand", vm) }
        try {
            vm.captured(ClipboardText.Text("captured once"))
            waitFor { vm.state is UnderstandState.Done }
            vm.recheckIfStale()
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(1, requests.size)

            context = context.copy(model = "slow-model")
            vm.recheckIfStale()
            waitFor { requests.size == 2 }
            assertTrue(vm.state is UnderstandState.Loading)
            context = UnderstandContext(Provider.OpenAI, "new-model", "Catalan")
            vm.recheckIfStale()
            waitFor { (vm.state as? UnderstandState.Done)?.result?.translation == "Catalan:new-model" }
            oldAnswer.complete(Unit)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals("Catalan:new-model", (vm.state as UnderstandState.Done).result.translation)
            assertEquals(List(3) { "captured once" }, requests.map { it.first })
            assertEquals(context, requests.last().second)
        } finally { oldAnswer.complete(Unit); store.clear() }
    }

    @Test fun aSettingsReadCannotRestartTheJobAfterCancellation() {
        val pendingContext = CompletableDeferred<Unit>()
        var blockContext = false
        var requests = 0
        val vm = UnderstandViewModel(context = {
            if (blockContext) withContext(NonCancellable) { pendingContext.await() }
            initialContext.copy(model = if (blockContext) "changed-model" else "first-model")
        }) { _, _ ->
            requests++
            Understood("Translation", emptyList())
        }
        val store = ViewModelStore().apply { put("understand", vm) }
        try {
            vm.captured(ClipboardText.Text("captured once"))
            waitFor { vm.state is UnderstandState.Done }
            blockContext = true
            vm.recheckIfStale()
            vm.cancel()
            pendingContext.complete(Unit)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(1, requests)
            assertTrue(vm.state is UnderstandState.Done)
        } finally { pendingContext.complete(Unit); store.clear() }
    }

    @Test fun returningAfterSavingAKeyRetriesAFailedRequestWithTheSameSettingsAndText() {
        var hasKey = false
        val inputs = mutableListOf<String>()
        val vm = UnderstandViewModel(context = { initialContext }) { text, _ ->
            inputs += text
            if (!hasKey) throw CheckFailure(CheckFailure.Reason.NoKey)
            Understood("Recovered", emptyList())
        }
        val store = ViewModelStore().apply { put("understand", vm) }
        try {
            vm.captured(ClipboardText.Text("captured once"))
            waitFor { vm.state is UnderstandState.Failed }
            hasKey = true
            vm.recheckIfStale()
            waitFor { vm.state is UnderstandState.Done }
            assertEquals(listOf("captured once", "captured once"), inputs)
        } finally { store.clear() }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > until) error("Timed out")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
