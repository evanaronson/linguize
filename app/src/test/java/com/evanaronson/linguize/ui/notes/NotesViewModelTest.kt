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

/** Notes: the list, a note's page that never loses text, and the check through the shared card out to Copy and Share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotesViewModelTest {
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

    /** This view model with note [id] (null: the current note) open on its page. */
    private fun NotesViewModel.opened(id: String? = null): NotesViewModel = also { open(id); settle() }

    @Test fun aFreshInstallOpensOnTheSampleWithoutSavingIt() {
        val write = write()
        assertTrue(write.listed)
        assertTrue(write.notes.isEmpty())
        var resolved: String? = "unset"
        assertTrue(write.open(null) { resolved = it })
        settle()
        assertEquals(sample, write.text)
        assertTrue(write.showingSample)
        // Not saved, so "the current note" stays unresolved.
        assertNull(resolved)
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
        assertTrue(write.notes.isEmpty())
    }

    @Test fun theSampleHintGoesOnceTheTextChanges() {
        val write = write().opened()
        write.edit("Hola")
        assertFalse(write.showingSample)
        write.opened(write.newNoteId())
        assertFalse(write.showingSample)
    }

    @Test fun nothingIsEditableUntilANoteIsOpen() {
        val write = write()
        write.edit("Hola")
        assertEquals("", write.text)
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
    }

    @Test fun typingIsSavedAfterAPauseAndOpensAgainNextTime() {
        val write = write().opened()
        write.edit("Hola, ")
        advance(100)
        assertTrue(store().load().drafts.isEmpty())
        write.edit("Hola, veí")
        write.selectLanguage("ca")
        advance(500)
        val saved = store().load().current
        assertEquals("Hola, veí", saved?.text)
        assertEquals("ca", saved?.language)
        assertEquals(listOf("Hola, veí"), write.notes.map { it.text })

        val again = write()
        var resolved: String? = null
        again.open(null) { resolved = it }
        settle()
        assertEquals("Hola, veí", again.text)
        assertEquals("ca", again.language)
        assertEquals(saved?.id, resolved)
    }

    @Test fun leavingSavesAtOnce() {
        val write = write().opened()
        write.edit("Benvolguda Sra. Puig,")
        write.flush()
        settle()
        assertEquals("Benvolguda Sra. Puig,", store().load().current?.text)
        // The pending autosave changes nothing more.
        advance(500)
        assertEquals(1, store().load().drafts.size)
    }

    @Test fun aNewNoteKeepsTheLastInTheListAndOpeningItBringsItBack() {
        val write = write().opened()
        write.edit("Primer missatge")
        val id = write.newNoteId()
        assertTrue(write.open(id))
        settle()
        assertEquals("", write.text)
        assertEquals(id, write.openId)
        assertEquals(listOf("Primer missatge"), write.notes.map { it.text })

        write.edit("Segon missatge")
        write.flush()
        settle()
        // Every note is in the list, newest first.
        assertEquals(listOf("Segon missatge", "Primer missatge"), write.notes.map { it.text })
        assertEquals(id, store().load().drafts.first().id)

        write.opened(write.notes.last().id)
        assertEquals("Primer missatge", write.text)
        // The last note opened is the one the app opens to next time.
        assertEquals("Primer missatge", write().opened().text)
    }

    @Test fun aNewNoteLeftBlankIsNotKept() {
        val write = write().opened()
        write.edit("Un")
        write.opened(write.newNoteId())
        write.flush()
        settle()
        assertEquals(listOf("Un"), store().load().drafts.map { it.text })
        // With no current note, the app opens to the newest.
        assertEquals("Un", write().opened().text)
    }

    @Test fun aNewNotesIdOpensItBlankAgainUntilSomethingIsWritten() {
        // As after the app was closed on a new note's page: its id, nothing stored.
        val write = write().opened("n1")
        assertEquals("", write.text)
        assertFalse(write.showingSample)
        write.edit("Hola")
        write.flush()
        settle()
        assertEquals("n1", store().load().current?.id)
        assertEquals("Hola", write().opened("n1").text)
    }

    @Test fun reopeningTheOpenNoteChangesNothing() {
        val write = write().opened()
        write.edit("Hola")
        write.flush()
        settle()
        val id = write.openId!!
        var resolved: String? = null
        assertFalse(write.open(id))
        assertFalse(write.open(null) { resolved = it })
        assertEquals(id, resolved)
        assertEquals("Hola", write.text)
    }

    @Test fun anEmptiedNoteIsNotKept() {
        val write = write().opened()
        write.edit("Hola")
        write.flush()
        settle()
        val id = write.openId
        write.edit("  ")
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
        assertTrue(write.notes.isEmpty())
        // Writing again keeps it, in place.
        write.edit("Adéu")
        write.flush()
        settle()
        assertEquals(listOf("Adéu"), store().load().drafts.map { it.text })
        assertEquals(id, store().load().drafts.single().id)
    }

    @Test fun deletingFromTheListCanBeUndone() {
        val write = write().opened()
        write.edit("Un")
        write.opened(write.newNoteId())
        val id = write.notes.single().id
        write.delete(id)
        settle()
        assertTrue(write.notes.isEmpty())
        assertEquals("Un", write.deleted?.text)
        assertTrue(store().load().drafts.isEmpty())

        write.undoDelete()
        settle()
        assertNull(write.deleted)
        assertEquals(listOf("Un"), write.notes.map { it.text })
        assertEquals(listOf("Un"), store().load().drafts.map { it.text })
    }

    @Test fun deletingTheLastOpenNoteKeepsItDeleted() {
        val write = write().opened()
        write.edit("Un")
        write.flush()
        settle()
        // Back on the list, the note just written is deleted, then another note is opened.
        write.delete(write.notes.single().id)
        write.opened(write.newNoteId())
        write.flush()
        settle()
        assertTrue(store().load().drafts.isEmpty())
        assertTrue(write.notes.isEmpty())
    }

    @Test fun writingPadDraftsOpenInNotes() {
        File(folder.root, WriteDraftStore.PAD_FILE_NAME).writeText(
            """{"version":1,"drafts":[{"id":"p1","title":"Escola","text":"Bon dia,","language":"ca","createdAt":1,"updatedAt":5},""" +
                """{"id":"p2","title":"","text":"Hola","createdAt":1,"updatedAt":2}]}""",
        )
        val write = write()
        assertEquals(listOf("Escola", "Hola"), write.notes.map { it.label() })
        write.opened()
        assertEquals("Bon dia,", write.text)
        assertEquals("ca", write.language)
        // Editing keeps the note's title for the list.
        write.edit("Bon dia, Sra. Puig")
        write.opened(write.newNoteId())
        assertEquals(listOf("Escola", "Hola"), write.notes.map { it.label() })
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
        val write = write().opened()
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
