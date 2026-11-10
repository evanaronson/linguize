package com.evanaronson.linguize.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.notes.NoteCheckCard
import com.evanaronson.linguize.ui.notes.NoteEditor
import com.evanaronson.linguize.ui.notes.NoteList
import com.evanaronson.linguize.ui.notes.NotesFooter
import com.evanaronson.linguize.ui.notes.NotesPart
import com.evanaronson.linguize.ui.notes.notesParts
import com.evanaronson.linguize.ui.notes.rememberNotesViewModel

/**
 * Notes (once Write), one of the four destinations: an editor with its notes, checked with the same card as
 * everywhere else (which also fills [gaps]). Until a key is saved ([needsKey]), a strip at the
 * top leads to it. Then the editor and its actions, the card under them, the other notes, and
 * the captions ([notesParts]).
 */
@Composable
internal fun NotesScreen(
    check: CheckViewModel,
    needsKey: Boolean,
    onOpenSettings: () -> Unit,
    /** Lab, Language scope: the app's current language, which the editor checks in. */
    language: String? = null,
) {
    val write = rememberNotesViewModel()
    val parts = notesParts(
        needsKey = needsKey,
        checking = check.state != null,
        hasOthers = write.drafts.isNotEmpty() || write.deleted != null,
    )
    ScreenColumn {
        // Order and grouping: docs/lab/ia-notes.md.
        for (part in parts) when (part) {
            NotesPart.AddKey -> AddKeyStrip(onOpenSettings)
            NotesPart.Editor -> NoteEditor(write, check, appLanguage = language)
            NotesPart.CheckCard -> NoteCheckCard(write, check, onOpenSettings)
            NotesPart.OtherNotes -> NoteList(write, onOpened = { check.dismiss(applied = false) })
            NotesPart.Footer -> NotesFooter()
        }
    }
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
