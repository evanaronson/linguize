package com.evanaronson.linguize.ui.home

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.data.chat.StartTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Notes is a list of notes, and each note opens on its own page above it with Back to the
 * list, as a conversation opens above Chat's list (docs/lab/notes.md). The Writing pad, which
 * opened its own activity, is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotesNavigationTest {
    @Test fun aNoteOpensAboveTheListAndBackReturnsToIt() {
        for (start in listOf(Navigation(Screen.Chat), Navigation(Screen.History))) {
            val list = start.open(Screen.Notes)
            val note = list.open(Screen.Note("a"))
            assertEquals(Screen.Notes, note.parent)
            assertEquals(Screen.Notes, note.tab)
            assertEquals(list, note.back())
            assertEquals(start, note.back().back())
        }
        // With Notes as the start, the list is the root: Back from it leaves the app.
        val note = Navigation().open(Screen.Note("a"))
        assertEquals(Navigation(), note.back())
        assertNull(note.back().parent)
    }

    @Test fun oneNoteAtATime() {
        // Another note (New note, or a row of the list) replaces the open one, so Back still leads to the list.
        val other = Navigation().open(Screen.Note("a")).open(Screen.Note("b"))
        assertEquals(listOf("notes", "lab_notes_note:b"), other.saved)
        assertEquals(Navigation(), other.back())
    }

    @Test fun theNotesTabFromANoteReturnsToTheList() {
        val note = Navigation(Screen.Chat).open(Screen.Notes).open(Screen.Note("a"))
        assertEquals(Navigation(Screen.Chat).open(Screen.Notes), note.open(Screen.Notes))
        assertEquals(Navigation(), Navigation().open(Screen.Note("a")).open(Screen.Notes))
        // Other tabs, Settings and the language selector's pages open from a note as from anywhere.
        assertEquals(Navigation(Screen.Chat).open(Screen.Progress), note.open(Screen.Progress))
        assertEquals(Screen.Note("a"), note.open(Screen.Languages).back().current)
    }

    @Test fun theStartOpensTheCurrentNoteAndItResolvesInPlace() {
        val start = startNavigation(StartTab.Notes, openSettings = false)
        assertEquals(Screen.Note(null), start.current)
        // Once the current note is known, its id replaces "the current note", the list still behind it.
        val resolved = start.replace(Screen.Note("n1"))
        assertEquals(listOf("notes", "lab_notes_note:n1"), resolved.saved)
        assertEquals(Navigation(), resolved.back())
    }

    @Test fun notesAndANoteRestoreFromSavedState() {
        for (path in listOf(
            Navigation().open(Screen.Note("a")),
            Navigation().open(Screen.Note(null)),
            Navigation(Screen.Chat).open(Screen.Notes).open(Screen.Note("a")).open(Screen.Languages),
            Navigation(Screen.Chat).open(Screen.Notes),
        )) assertEquals(path, Navigation.restore(path.saved))
        assertEquals(Screen.Note(null), Screen.restore("lab_notes_note"))
        assertEquals(Screen.Note("x"), Screen.restore("lab_notes_note:x"))
        assertNull(Screen.restore("lab_notes_note:"))
        // Saved before notes had pages: the list.
        assertEquals(Navigation(), Navigation.restore(listOf("notes")))
        assertEquals(Navigation(), Navigation.restore(listOf("home")))
    }

    @Test fun switchingTheLanguageKeepsTheNoteOpen() {
        // A note's checks follow the app's language, and its page keeps the selector.
        val note = Navigation().open(Screen.Note("a"))
        assertEquals(note, note.afterLanguageSwitch())
    }

    @Test fun theWritingPadActivityIsNoLongerDeclared() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pad = Intent().setClassName(context, "com.evanaronson.linguize.menu.WritingPadActivity")
        assertNull(context.packageManager.resolveActivity(pad, 0))
        // The manifest is the app's: the activity hosting Notes is declared.
        assertNotNull(context.packageManager.resolveActivity(Intent(context, HomeActivity::class.java), 0))
    }
}
