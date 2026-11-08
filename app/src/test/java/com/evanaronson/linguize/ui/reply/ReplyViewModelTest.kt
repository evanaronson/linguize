package com.evanaronson.linguize.ui.reply

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntent
import com.evanaronson.linguize.core.reply.ReplyIntents
import com.evanaronson.linguize.core.reply.ReplySentence
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
class ReplyViewModelTest {
    private val accept = ReplyIntent("Accept", "Say yes, Saturday works.")
    private fun draftOf(text: String) = ReplyDraft("Spanish", listOf(ReplySentence(text, "gloss of $text", false)), emptyList())

    private data class Write(val message: String, val translation: String, val intent: String, val simplerThan: String?)

    @Test fun suggestionsLoadAndChoosingOneDraftsFromItsInstruction() = withModel(
        suggest = { _, _ -> ReplyIntents("Spanish", listOf(accept)) },
    ) { vm, writes ->
        vm.start("¿Vienes el sábado?", "Are you coming on Saturday?")
        assertEquals(ReplyState.Describing, vm.state)
        waitFor { vm.suggestions is ReplySuggestions.Ready }
        vm.choose(accept)
        waitFor { vm.state is ReplyState.Drafted }
        assertEquals(Write("¿Vienes el sábado?", "Are you coming on Saturday?", accept.instruction, null), writes.single())
        assertEquals(accept.instruction, vm.intent)
        assertEquals("draft for ${accept.instruction}", vm.reply)
    }

    @Test fun failedSuggestionsStillLetTheWriterTypeAndBlankIntentsDraftNothing() = withModel(
        suggest = { _, _ -> throw CheckFailure(CheckFailure.Reason.Offline) },
    ) { vm, writes ->
        vm.start("Hola", "Hi")
        waitFor { vm.suggestions == ReplySuggestions.Unavailable }
        vm.changeIntent("   ")
        assertFalse(vm.draftTyped())
        assertEquals(ReplyState.Describing, vm.state)
        vm.changeIntent("x".repeat(5_000))
        assertEquals(1_000, vm.intent.length)
        vm.changeIntent("  Digues que sí  ")
        assertTrue(vm.draftTyped())
        waitFor { vm.state is ReplyState.Drafted }
        assertEquals("Digues que sí", writes.single().intent)
    }

    @Test fun editsAreTrackedAndUndoneWithoutChangingTheDraft() = withModel { vm, _ ->
        vm.start("Hola", "Hi")
        vm.changeIntent("Say hi")
        vm.draftTyped()
        waitFor { vm.state is ReplyState.Drafted }
        assertFalse(vm.isEdited)
        vm.edit("draft for Say hi")
        assertFalse("the same text is not an edit", vm.isEdited)
        vm.edit("Hola, ¿qué tal?")
        assertTrue(vm.isEdited)
        assertEquals("Hola, ¿qué tal?", vm.reply)
        assertEquals("draft for Say hi", (vm.state as ReplyState.Drafted).draft.text)
        vm.undoEdit()
        assertFalse(vm.isEdited)
        assertEquals("draft for Say hi", vm.reply)
    }

    @Test fun simplerSendsThePreviousDraftAndRetryKeepsIt() {
        var fail = false
        withModel(write = { _, _, intent, simpler ->
            if (fail) throw CheckFailure(CheckFailure.Reason.Timeout)
            draftOf(if (simpler == null) "long $intent" else "short $intent")
        }) { vm, writes ->
            vm.start("Hola", "Hi")
            vm.changeIntent("Say hi")
            vm.draftTyped()
            waitFor { vm.state is ReplyState.Drafted }
            fail = true
            vm.simpler()
            waitFor { vm.state is ReplyState.Failed }
            fail = false
            vm.retry()
            waitFor { vm.state is ReplyState.Drafted }
            assertEquals(listOf(null, "long Say hi", "long Say hi"), writes.map { it.simplerThan })
            assertEquals("short Say hi", vm.reply)
        }
    }

    @Test fun describingAgainOrANewMessageIgnoresALateDraft() {
        val gate = CompletableDeferred<Unit>()
        withModel(write = { _, _, intent, _ ->
            if (intent == "slow") withContext(NonCancellable) { gate.await() }
            draftOf(intent)
        }) { vm, writes ->
            vm.start("Hola", "Hi")
            vm.changeIntent("slow")
            vm.draftTyped()
            waitFor { writes.size == 1 }
            vm.describeAgain()
            assertEquals(ReplyState.Describing, vm.state)
            assertEquals("slow", vm.intent)
            gate.complete(Unit)
            repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2) }
            assertEquals(ReplyState.Describing, vm.state)

            vm.changeIntent("fast")
            vm.draftTyped()
            waitFor { vm.state is ReplyState.Drafted }
            vm.start("Hola", "Hi")
            assertTrue("reopening the same message keeps its draft", vm.state is ReplyState.Drafted)
            vm.start("Adéu", "Bye")
            assertEquals(ReplyState.Describing, vm.state)
            assertEquals("", vm.intent)
            assertNull(vm.reply)
        }
    }

    private fun withModel(
        suggest: suspend (String, String) -> ReplyIntents = { _, _ -> ReplyIntents("Spanish", listOf(accept)) },
        write: (suspend (String, String, String, String?) -> ReplyDraft)? = null,
        test: (ReplyViewModel, List<Write>) -> Unit,
    ) {
        val writes = java.util.Collections.synchronizedList(mutableListOf<Write>())
        val vm = ReplyViewModel(suggest) { message, translation, intent, simpler ->
            writes += Write(message, translation, intent, simpler)
            write?.invoke(message, translation, intent, simpler) ?: draftOf("draft for $intent")
        }
        val store = ViewModelStore().apply { put("reply", vm) }
        try {
            test(vm, writes)
        } finally {
            store.clear()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
    }
}
