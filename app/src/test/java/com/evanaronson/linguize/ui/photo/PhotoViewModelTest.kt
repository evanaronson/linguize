package com.evanaronson.linguize.ui.photo

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.UnderstandContext
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.lab.ImageInput
import com.evanaronson.linguize.llm.lab.PhotoPrompt
import com.evanaronson.linguize.llm.lab.PhotoReading
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoViewModelTest {
    private val first = UnderstandContext(Provider.Gemini, "first-model", "English")
    private val reading = PhotoReading(true, "Catalan", "Bon dia", "", "Good morning", emptyList(), emptyList())

    private fun withStore(vm: PhotoViewModel, test: () -> Unit) {
        val store = ViewModelStore().apply { put("photo", vm) }
        try { test() } finally { store.clear() }
    }

    @Test fun opensOnceThenRetriesWithTheSameImageInMemory() {
        val image = ImageInput("image/jpeg", byteArrayOf(1, 2))
        var opened = 0
        val sent = mutableListOf<ImageInput>()
        val vm = PhotoViewModel(context = { first }) { input, _ ->
            sent += input
            if (sent.size == 1) throw CheckFailure(CheckFailure.Reason.Offline)
            reading
        }
        withStore(vm) {
            vm.understand { opened++; image }
            waitFor { vm.state is PhotoState.Failed }
            vm.retry()
            waitFor { vm.state is PhotoState.Done }
            assertEquals(PhotoState.Done(reading), vm.state)
            assertEquals(1, opened)
            assertEquals(2, sent.size)
            sent.forEach { assertSame(image, it) }
        }
    }

    @Test fun anImageThatWontOpenIsNeverSent() {
        var sent = 0
        val vm = PhotoViewModel(context = { first }) { _, _ -> sent++; reading }
        withStore(vm) {
            vm.understand { error("Not an image") }
            waitFor { vm.state == PhotoState.CantOpen }
            // Nothing to retry with.
            vm.retry()
            assertEquals(PhotoState.CantOpen, vm.state)
            assertEquals(0, sent)
        }
    }

    @Test fun anImageTheEngineRefusesShowsAsOneThatWontOpen() {
        val vm = PhotoViewModel(context = { first }) { _, _ -> throw IllegalArgumentException("too big") }
        withStore(vm) {
            vm.understand { ImageInput("image/jpeg", byteArrayOf(1)) }
            waitFor { vm.state == PhotoState.CantOpen }
        }
    }

    @Test fun aNewPhotoReplacesALateAnswerForTheOldOne() {
        val gate = CompletableDeferred<Unit>()
        val old = ImageInput("image/jpeg", byteArrayOf(1))
        val new = ImageInput("image/jpeg", byteArrayOf(2))
        val vm = PhotoViewModel(context = { first }) { input, _ ->
            if (input === old) {
                gate.await()
                PhotoPrompt.UNREADABLE
            } else {
                reading
            }
        }
        withStore(vm) {
            vm.understand { old }
            waitFor { vm.state == PhotoState.Reading }
            vm.understand { new }
            gate.complete(Unit)
            waitFor { vm.state is PhotoState.Done }
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(PhotoState.Done(reading), vm.state)
        }
    }

    @Test fun returningFromSettingsReadsAgainOnlyWhenSomethingChanged() {
        var context = first
        val used = mutableListOf<UnderstandContext>()
        val vm = PhotoViewModel(context = { context }) { _, ctx -> used += ctx; reading }
        withStore(vm) {
            vm.understand { ImageInput("image/jpeg", byteArrayOf(1)) }
            waitFor { vm.state is PhotoState.Done }
            vm.recheckIfStale()
            repeat(10) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(1, used.size)
            context = first.copy(model = "second-model")
            vm.recheckIfStale()
            waitFor { used.size == 2 }
            assertEquals("second-model", used.last().model)
        }
    }

    @Test fun aLimitedShareOpensAndSendsNothing() {
        var sent = 0
        val vm = PhotoViewModel(context = { first }) { _, _ -> sent++; reading }
        withStore(vm) {
            vm.limited()
            assertEquals(PhotoState.TooMany, vm.state)
            vm.retry()
            assertEquals(PhotoState.TooMany, vm.state)
            assertEquals(0, sent)
        }
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
