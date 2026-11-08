package com.evanaronson.linguize.ui.gaps

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.lab.GapFill
import com.evanaronson.linguize.core.lab.GapHint
import com.evanaronson.linguize.core.lab.QuotedFill
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GapFillViewModelTest {
    private val source = "Hola, ¿puedes [pick me up] a las 8?"
    private val filled = "Hola, ¿puedes recogerme a las 8?"

    private fun answer(text: String) = GapFill.place(text, "Spanish", listOf(
        QuotedFill("[pick me up]", "recogerme", "", listOf("venir a buscarme")),
    ))

    @Test fun failuresRetryWithTheSameTextAndLanguageAndChoicesChangeTheResult() = withModel { calls, vm ->
        vm.start("  $source  ", Language.Spanish)
        waitFor { vm.state is GapState.Failed }
        assertEquals(source, vm.state!!.text)
        vm.retryIfFailed()
        waitFor { vm.state is GapState.Done }
        assertEquals(listOf(source to Language.Spanish, source to Language.Spanish), calls)
        assertEquals(filled, vm.result)
        vm.choose(0, 1)
        assertEquals("Hola, ¿puedes venir a buscarme a las 8?", vm.result)
        // Done results are not retried on return from settings.
        vm.retryIfFailed()
        assertEquals(2, calls.size)
        vm.changeLanguage(null)
        waitFor { calls.size == 3 }
        assertEquals(source to null, calls.last())
    }

    @Test fun aLateAnswerForAClosedFillIsDropped() {
        val gate = CompletableDeferred<Unit>()
        val vm = GapFillViewModel { text, _ ->
            withContext(NonCancellable) { gate.await() }
            answer(text)
        }
        val store = ViewModelStore().apply { put("gaps", vm) }
        try {
            vm.start(source, null)
            assertTrue(vm.state is GapState.Loading)
            vm.close()
            gate.complete(Unit)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertNull(vm.state)
            vm.start("   ", null)
            assertNull(vm.state)
        } finally {
            store.clear()
        }
    }

    @Test fun aPopupPutsBackTheUsedFillOrTheCheckedFillWithItsAcceptedChanges() = withModel(failFirst = false) { _, vm ->
        vm.start(source, null)
        waitFor { vm.state is GapState.Done }
        // Before anything is chosen, the check's accepted changes go back as before.
        assertEquals("check text", vm.replacement("check text"))
        assertTrue(vm.checkApplied(true, "check text"))
        assertEquals(source, vm.source(source, null))

        // Check it: the fill becomes the text the check is about, and the card returns to the check.
        assertEquals(filled, vm.check())
        assertNull(vm.state)
        assertEquals(filled, vm.source(source, null))
        assertNull(GapOffer.of(vm.source(source, null)) {})
        assertEquals(filled, vm.replacement(null))
        assertFalse(vm.checkApplied(true, null))
        assertEquals("$filled!", vm.replacement("$filled!"))
        assertTrue(vm.checkApplied(true, "$filled!"))
    }

    @Test fun putBackUsesTheFillWhateverTheCheckAccepted() = withModel(failFirst = false) { _, vm ->
        assertEquals(GapHint.Marked(1), GapOffer.of(source) {}!!.hint)
        vm.start(source, null)
        waitFor { vm.state is GapState.Done }
        assertEquals(filled, vm.use())
        assertEquals(filled, vm.replacement("Hola, ¿puedes [pick me up] a las ocho?"))
        assertFalse(vm.checkApplied(true, "Hola, ¿puedes [pick me up] a las ocho?"))
    }

    /** A view model that answers (after failing offline once, by default); [calls] lists each request. */
    private fun withModel(failFirst: Boolean = true, test: (MutableList<Pair<String, Language?>>, GapFillViewModel) -> Unit) {
        val calls = mutableListOf<Pair<String, Language?>>()
        val vm = GapFillViewModel { text, language ->
            calls += text to language
            if (failFirst && calls.size == 1) throw CheckFailure(CheckFailure.Reason.Offline)
            answer(text)
        }
        val store = ViewModelStore().apply { put("gaps", vm) }
        try {
            test(calls, vm)
        } finally {
            store.clear()
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
