package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.LocalCardTyping
import com.evanaronson.linguize.ui.card.rememberCardTyping
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.notes.NoteCheckCard
import com.evanaronson.linguize.ui.notes.NoteDeletedRow
import com.evanaronson.linguize.ui.notes.NoteEditor
import com.evanaronson.linguize.ui.notes.NoteList
import com.evanaronson.linguize.ui.notes.NotePart
import com.evanaronson.linguize.ui.notes.NotesEmpty
import com.evanaronson.linguize.ui.notes.NotesFooter
import com.evanaronson.linguize.ui.notes.NotesListPart
import com.evanaronson.linguize.ui.notes.NotesStorageError
import com.evanaronson.linguize.ui.notes.NotesViewModel
import com.evanaronson.linguize.ui.notes.noteParts
import com.evanaronson.linguize.ui.notes.notesListParts
import com.evanaronson.linguize.ui.notes.rememberNotesViewModel

/**
 * Notes (once Write), one of the four destinations: the notes, newest first, with **New note**
 * as the main button at the bottom, as Chat has New conversation. A note opens on its own page
 * above the list ([NotePage], [Screen.Note]), with Back to it. See docs/lab/notes.md.
 */
@Composable
internal fun NotesScreen(onOpen: (String) -> Unit) {
    val write = rememberNotesViewModel()
    val parts = notesListParts(listed = write.listed, hasNotes = write.notes.isNotEmpty(), justDeleted = write.deleted != null)
    Box(Modifier.fillMaxSize()) {
        ScreenColumn(Modifier.fillMaxSize()) {
            NotesStorageError(write)
            for (part in parts) when (part) {
                NotesListPart.Undo -> NoteDeletedRow(write)
                NotesListPart.Notes -> NoteList(write, onOpen)
                NotesListPart.Empty -> NotesEmpty()
            }
            // Room for New note over the last row.
            Spacer(Modifier.height(80.dp))
        }
        ExtendedFloatingActionButton(
            onClick = { onOpen(write.newNoteId()) },
            icon = { Icon(painterResource(R.drawable.lab_chat_ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.lab_notes_new)) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

/**
 * One note's page, above the Notes list: the editor checked with the same card as everywhere
 * else (which also fills [gaps]), in the app's [language]. Until a key is saved ([needsKey]), a
 * strip at the top leads to it. [noteId] null is "the current note", what the app opens to when
 * Notes is the start; [onResolved] gets its id once it's known (null: a new note, or the sample).
 * Opening another note closes the card, which was for the other note's text.
 */
@Composable
internal fun NotePage(
    noteId: String?,
    check: CheckViewModel,
    needsKey: Boolean,
    onOpenSettings: () -> Unit,
    onResolved: (String?) -> Unit,
    /** Lab, Language scope: the app's current language, which the editor checks in. */
    language: String? = null,
) {
    val write = rememberNotesViewModel()
    val resolved by rememberUpdatedState(onResolved)
    LaunchedEffect(write, noteId) {
        if (write.open(noteId) { resolved(it) }) check.dismiss(applied = false)
    }
    ResolveSavedNoteRoute(write) { resolved(it) }
    // Lab, Type to accept: while a change is typed on the card, the page ends above the keyboard,
    // so the card can keep the change and the field in view together.
    val typing = rememberCardTyping()
    ScreenColumn(if (typing.active) Modifier.imePadding() else Modifier) {
        NotesStorageError(write)
        // Order and grouping: docs/lab/ia-notes.md.
        for (part in noteParts(needsKey = needsKey, checking = check.state != null)) when (part) {
            NotePart.AddKey -> AddKeyStrip(onOpenSettings)
            NotePart.Editor -> NoteEditor(write, check, appLanguage = language)
            NotePart.CheckCard -> CompositionLocalProvider(LocalCardTyping provides typing) {
                NoteCheckCard(write, check, onOpenSettings)
            }
            NotePart.Footer -> NotesFooter()
        }
    }
}

/** A new current note gets a stable route only after its first successful save. */
@Composable
internal fun ResolveSavedNoteRoute(write: NotesViewModel, onResolved: (String) -> Unit) {
    val resolved by rememberUpdatedState(onResolved)
    val savedId = write.savedId
    LaunchedEffect(write, savedId) { savedId?.let { resolved(it) } }
}

/** First run: nothing can be checked until there is a key. */
@Composable
private fun AddKeyStrip(onAddKey: () -> Unit) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_key_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.add_key_description), style = MaterialTheme.typography.bodySmall)
        }
        LinguizeButton(stringResource(R.string.add_key), onAddKey, ButtonKind.Text)
    }
}
