package com.evanaronson.linguize.ui.notes

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.FakeClient
import com.evanaronson.linguize.FakePreferences
import com.evanaronson.linguize.QUESTION
import com.evanaronson.linguize.QUESTION_JSON
import com.evanaronson.linguize.QUESTION_VERDICT
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.VerdictChange
import com.evanaronson.linguize.data.write.WriteDraftStore
import com.evanaronson.linguize.fakeChecker
import com.evanaronson.linguize.history.CheckHistory
import com.evanaronson.linguize.history.HistoryEnvironment
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.history.SqliteHistoryStore
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.ui.card.CardState
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.copyToClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Write's editor: drafts that survive leaving, and the check through the shared card out to Copy and Share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WriteViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val sample = "Bon dia! Com estas amb la pluja?"
    private val viewModels = mutableListOf<ViewModelStore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ids = 0
    private var now = 100L

    @After
    fun stop() {
        viewModels.forEach { it.clear() }
        scope.cancel()
    }

    private fun store() = WriteDraftStore(
        File(folder.root, WriteDraftStore.FILE_NAME),
        legacy = File(folder.root, WriteDraftStore.PAD_FILE_NAME),
    )

    /** A fresh view model on the same files, as after the app was closed and opened again. */
    private fun write(): NotesViewModel {
        val owner = ViewModelStore().also { viewModels += it }
        return ViewModelProvider(owner, viewModelFactory {
            initializer {
                NotesViewModel(store(), { sample }, saveMillis = 400, clock = { now++ },
                    io = Dispatchers.Unconfined, newId = { "d${++ids}" })
            }
        })[NotesViewModel::class.java].also { settle() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test fun aFreshInstallShowsTheSampleWithoutSavingIt() {
        val write = write()
        assertEquals(sample, write.text)
        assertTrue(write.showingSample)
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
        assertTrue(write.drafts.isEmpty())
    }

    @Test fun theSampleHintGoesOnceTheTextChanges() {
        val write = write()
        write.edit("Hola")
        assertFalse(write.showingSample)
        write.newDraft()
        settle()
        assertFalse(write.showingSample)
    }

    @Test fun typingIsSavedAfterAPauseAndOpensAgainNextTime() {
        val write = write()
        write.edit("Hola, ")
        advance(100)
        assertTrue(store().load().drafts.isEmpty())
        write.edit("Hola, veí")
        write.selectLanguage("ca")
        advance(500)
        val saved = store().load().current
        assertEquals("Hola, veí", saved?.text)
        assertEquals("ca", saved?.language)

        val again = write()
        assertEquals("Hola, veí", again.text)
        assertEquals("ca", again.language)
    }

    @Test fun leavingSavesAtOnce() {
        val write = write()
        write.edit("Benvolguda Sra. Puig,")
        write.flush()
        settle()
        assertEquals("Benvolguda Sra. Puig,", store().load().current?.text)
        // The pending autosave changes nothing more.
        advance(500)
        assertEquals(1, store().load().drafts.size)
    }

    @Test fun newDraftKeepsTheLastInTheListAndOpeningItBringsItBack() {
        val write = write()
        write.edit("Primer missatge")
        write.newDraft()
        settle()
        assertEquals("", write.text)
        assertEquals(listOf("Primer missatge"), write.drafts.map { it.text })

        write.edit("Segon missatge")
        write.open(write.drafts.single().id)
        settle()
        assertEquals("Primer missatge", write.text)
        assertEquals(listOf("Segon missatge"), write.drafts.map { it.text })
        assertEquals("Primer missatge", write().text)
    }

    @Test fun anEmptiedDraftIsNotKept() {
        val write = write()
        write.edit("Hola")
        write.flush()
        settle()
        write.edit("  ")
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
        // Writing again starts a new draft.
        write.edit("Adéu")
        write.flush()
        settle()
        assertEquals(listOf("Adéu"), store().load().drafts.map { it.text })
    }

    @Test fun deletingFromTheListCanBeUndone() {
        val write = write()
        write.edit("Un")
        write.newDraft()
        settle()
        val id = write.drafts.single().id
        write.delete(id)
        settle()
        assertTrue(write.drafts.isEmpty())
        assertEquals("Un", write.deleted?.text)
        assertTrue(store().load().drafts.isEmpty())

        write.undoDelete()
        settle()
        assertNull(write.deleted)
        assertEquals(listOf("Un"), write.drafts.map { it.text })
        assertEquals(listOf("Un"), store().load().drafts.map { it.text })
    }

    @Test fun writingPadDraftsOpenInWrite() {
        File(folder.root, WriteDraftStore.PAD_FILE_NAME).writeText(
            """{"version":1,"drafts":[{"id":"p1","title":"Escola","text":"Bon dia,","language":"ca","createdAt":1,"updatedAt":5},""" +
                """{"id":"p2","title":"","text":"Hola","createdAt":1,"updatedAt":2}]}""",
        )
        val write = write()
        assertEquals("Bon dia,", write.text)
        assertEquals("ca", write.language)
        assertEquals(listOf("Hola"), write.drafts.map { it.text })
        // Editing keeps the draft's title for the list.
        write.edit("Bon dia, Sra. Puig")
        write.newDraft()
        settle()
        assertEquals(setOf("Escola", "Hola"), write.drafts.map { it.label() }.toSet())
    }

    @Test fun theCardsAcceptedChangesGoIntoTheDraftAndOutThroughCopyAndShare() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val client = FakeClient {
            ModelAnswer(QUESTION_VERDICT.copy(fixes = listOf(VerdictChange("estas", "estàs", "Accent", 10, 3))), QUESTION_JSON)
        }
        val history = CheckHistory(object : HistoryStore by SqliteHistoryStore(context, "unused") {}, object : HistoryEnvironment {
            override val enabled = false
            override val deviceId = "device"
            override val appVersion = "1.0"
        }, scope)
        val owner = ViewModelStore().also { viewModels += it }
        val checker = fakeChecker(FakePreferences(), client)
        val check = ViewModelProvider(owner, viewModelFactory {
            initializer { CheckViewModel(checker = { checker }, history = history) }
        })[CheckViewModel::class.java]
        val write = write()
        write.edit("  $QUESTION\n")

        check.check(write.text.trim(), null, Origin.Tester)
        waitFor { check.state is CardState.Done }
        check.acceptAll(EditKind.Fix)

        // Copy straight after accepting: the change is in what's copied and in the draft, and the card closes.
        val out = takeFromCard(write, check)
        assertEquals("  Com estàs?\n", out)
        assertEquals(out, write.text)
        assertNull(check.state)
        context.copyToClipboard(out)
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(out, clip?.getItemAt(0)?.text?.toString())

        // With no card open, the draft goes out as it is.
        assertEquals(out, takeFromCard(write, check))
        val share = shareIntent(context, out)
        assertEquals(Intent.ACTION_CHOOSER, share.action)
        @Suppress("DEPRECATION")
        val sent = share.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, sent?.action)
        assertEquals(out, sent?.getStringExtra(Intent.EXTRA_TEXT))

        advance(500)
        assertEquals(out, store().load().current?.text)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out")
            settle()
            Thread.sleep(2)
        }
    }
}
