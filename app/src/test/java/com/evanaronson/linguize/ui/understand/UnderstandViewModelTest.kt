package com.evanaronson.linguize.ui.understand

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.llm.CheckFailure
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
    @Test fun retryUsesTheCapturedTextAndCancellationRejectsLateAnswers() {
        val inputs = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val vm = UnderstandViewModel { text ->
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

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > until) error("Timed out")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
