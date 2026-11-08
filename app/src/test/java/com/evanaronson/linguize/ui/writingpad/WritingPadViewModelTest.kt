package com.evanaronson.linguize.ui.writingpad

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.data.writingpad.DraftStore
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WritingPadViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val idle = 1500L

    /** One call of the fake checker: its text, and the answer the test hands it. */
    private class Call(val text: String, val language: Language?) {
        val answer = CompletableDeferred<CheckResult>()
        var cancelled = false
    }

    private val calls = mutableListOf<Call>()

    private fun pad(): Pair<WritingPadViewModel, DraftStore> {
        val store = DraftStore(File(folder.root, DraftStore.FILE_NAME))
        val vm = WritingPadViewModel(
            store = store,
            check = { text, language ->
                val call = Call(text, language)
                calls += call
                try {
                    call.answer.await()
                } catch (e: CancellationException) {
                    call.cancelled = true
                    throw e
                }
            },
            idleMillis = idle,
            saveMillis = 300,
            maxChars = 3000,
            io = Dispatchers.Unconfined,
        )
        return vm to store
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun reviewed(checked: String): CheckResult = interpret(checked, Verdict(
        status = Verdict.Status.Ok,
        language = "Catalan",
        meaning = "How are you?",
        hasErrors = true,
        corrected = checked.replace("estas", "estàs"),
        fixes = listOf(VerdictChange("estas", "estàs", "Accent on the verb.")),
    ))

    private fun WritingPadViewModel.type(text: String) {
        onValueChange(TextFieldValue(text, TextRange(text.length)))
    }

    private fun withPad(block: (WritingPadViewModel, DraftStore) -> Unit) {
        val (vm, store) = pad()
        val owner = ViewModelStore().apply { put("pad", vm) }
        try {
            settle()
            vm.newDraft()
            settle()
            assertNotNull(vm.editor)
            block(vm, store)
        } finally {
            calls.forEach { it.answer.cancel() }
            owner.clear()
        }
    }

    @Test fun typingChecksOnceAfterThePauseWithTheLatestText() = withPad { vm, _ ->
        var text = ""
        for (word in "Com estas avui amic".split(" ")) {
            text = if (text.isEmpty()) word else "$text $word"
            vm.type(text)
            advance(400)
        }
        assertEquals(0, calls.size)
        assertEquals(PadStatus.Waiting, vm.editor!!.status)
        advance(idle)
        assertEquals(listOf("Com estas avui amic"), calls.map { it.text })
        assertEquals(PadStatus.Checking, vm.editor!!.status)

        calls.single().answer.complete(reviewed(text))
        settle()
        val editor = vm.editor!!
        assertEquals(PadStatus.Found(1, 0), editor.status)
        assertEquals("estas", editor.suggestions.single().from)
        assertEquals("How are you?", editor.meaning)
        assertTrue(editor.meaningCurrent)
        // Nothing changed: no second check, however long the pause.
        advance(idle * 5)
        assertEquals(1, calls.size)
    }

    @Test fun aNewerCheckCancelsTheOlderOneAndItsLateAnswerIsIgnored() = withPad { vm, _ ->
        vm.type("Com estas avui")
        advance(idle)
        val first = calls.single()
        // Typing while the first runs does not cancel it.
        vm.type("Com estas avui amic")
        advance(100)
        assertTrue(!first.cancelled)
        advance(idle)
        assertEquals(2, calls.size)
        assertTrue(first.cancelled)
        calls[1].answer.complete(reviewed("Com estas avui amic"))
        settle()
        assertEquals(1, vm.editor!!.suggestions.size)
        // Even if the old call returned now, nothing would change: its job is gone.
        first.answer.complete(CheckResult.Unclear)
        settle()
        assertEquals(PadStatus.Found(1, 0), vm.editor!!.status)
    }

    @Test fun anAnswerForOlderTextIsMovedOntoWhatWasTypedMeanwhile() = withPad { vm, _ ->
        vm.type("Com estas?")
        advance(idle)
        vm.type("Hola! Com estas?")
        calls.single().answer.complete(reviewed("Com estas?"))
        settle()
        val suggestion = vm.editor!!.suggestions.single()
        assertEquals("Hola! Com estas?".indexOf("estas"), suggestion.start)
        assertTrue(!vm.editor!!.meaningCurrent)
        // The pause after typing checks the new text.
        advance(idle)
        assertEquals("Hola! Com estas?", calls.last().text)
    }

    @Test fun tappingAnUnderlineShowsItAndAcceptPutsItIn() = withPad { vm, store ->
        vm.type("Com estas?")
        advance(idle)
        calls.single().answer.complete(reviewed("Com estas?"))
        settle()
        assertEquals(emptyList<Any>(), vm.editor!!.focused)
        // A tap puts the cursor in the word, without changing the text.
        vm.onValueChange(TextFieldValue("Com estas?", TextRange(6)))
        val focused = vm.editor!!.focused.single()
        vm.accept(focused.key)
        val editor = vm.editor!!
        assertEquals("Com estàs?", editor.value.text)
        assertEquals(TextRange(9), editor.value.selection)
        assertEquals(PadStatus.SoundsNatural, editor.status)
        assertEquals(emptyList<Any>(), editor.focused)
        // Accepting doesn't ask for another check, and the draft is saved.
        advance(idle * 3)
        assertEquals(1, calls.size)
        assertEquals("Com estàs?", store.get(editor.draftId)!!.text)
    }

    @Test fun dismissHidesTheChange() = withPad { vm, _ ->
        vm.type("Com estas?")
        advance(idle)
        calls.single().answer.complete(reviewed("Com estas?"))
        settle()
        vm.nextChange()
        val key = vm.editor!!.focused.single().key
        vm.dismiss(key)
        assertEquals(emptyList<Any>(), vm.editor!!.suggestions)
        assertEquals(PadStatus.SoundsNatural, vm.editor!!.status)
    }

    @Test fun aFailureWaitsForRetry() = withPad { vm, _ ->
        vm.type("Com estas?")
        advance(idle)
        calls.single().answer.completeExceptionally(CheckFailure(CheckFailure.Reason.Offline))
        settle()
        val status = vm.editor!!.status
        assertTrue(status is PadStatus.Failed && status.failure.reason == CheckFailure.Reason.Offline)
        advance(idle * 3)
        assertEquals(1, calls.size)
        vm.retry()
        settle()
        assertEquals(2, calls.size)
        assertEquals(PadStatus.Checking, vm.editor!!.status)
    }

    @Test fun languageChoiceIsUsedAndSaved() = withPad { vm, store ->
        vm.type("Com estas?")
        vm.setLanguage("ca")
        advance(idle)
        assertEquals(Language.Catalan, calls.single().language)
        advance(400)
        assertEquals("ca", store.get(vm.editor!!.draftId)!!.language)
    }

    @Test fun draftsAutosaveAndAnEmptyDraftIsNotKept() = withPad { vm, store ->
        val id = vm.editor!!.draftId
        vm.onTitleChange("Landlord")
        vm.type("Hola Joan")
        advance(400)
        assertEquals("Hola Joan", store.get(id)!!.text)
        assertEquals("Landlord", store.get(id)!!.title)
        vm.close()
        settle()
        assertEquals(null, vm.editor)
        assertEquals(listOf(id), vm.drafts!!.map { it.id })

        vm.newDraft()
        settle()
        val empty = vm.editor!!.draftId
        vm.close()
        settle()
        assertEquals(null, store.get(empty))
        assertEquals(listOf(id), vm.drafts!!.map { it.id })
    }

    @Test fun closingStopsTheRunningCheck() = withPad { vm, _ ->
        vm.type("Com estas?")
        advance(idle)
        vm.close()
        settle()
        assertTrue(calls.single().cancelled)
    }

    @Test fun homeOpensTheActivityTheManifestDeclares() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent: Intent = writingPadIntent(context)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        assertEquals(WRITING_PAD_ACTIVITY, resolved?.activityInfo?.name)
        // The name is the real class.
        Class.forName(WRITING_PAD_ACTIVITY)
    }
}
