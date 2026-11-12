package com.evanaronson.linguize.ui.notes

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.evanaronson.linguize.App
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Selection
import com.evanaronson.linguize.history.Origin
import com.evanaronson.linguize.ui.card.CardActions
import com.evanaronson.linguize.ui.card.CheckViewModel
import com.evanaronson.linguize.ui.card.LanguageHint
import com.evanaronson.linguize.ui.card.LocalLanguageHint
import com.evanaronson.linguize.ui.card.ResultCard
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.LinguizeTextField
import com.evanaronson.linguize.ui.components.ListCard
import com.evanaronson.linguize.ui.components.ListRow
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.components.shortDate
import com.evanaronson.linguize.ui.components.uiLocale
import com.evanaronson.linguize.ui.copyToClipboard
import com.evanaronson.linguize.ui.openExternal

@Composable
internal fun rememberNotesViewModel(): NotesViewModel =
    viewModel(factory = NotesViewModel.factory(App.of(LocalContext.current)))

/** A failed local operation stays visible until it succeeds; Retry keeps its note or Undo. */
@Composable
internal fun NotesStorageError(write: NotesViewModel) {
    val failure = write.storageFailure ?: return
    val message = when (failure) {
        NotesStorageFailure.Read -> R.string.lab_notes_read_failed
        NotesStorageFailure.Open -> R.string.lab_notes_open_failed
        NotesStorageFailure.Save -> R.string.lab_notes_save_failed
        NotesStorageFailure.Delete -> R.string.lab_notes_delete_failed
        NotesStorageFailure.Restore -> R.string.lab_notes_restore_failed
    }
    SectionCard {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        LinguizeButton(stringResource(R.string.retry), write::retryStorage, ButtonKind.Text)
    }
}

/**
 * A note's page: "Write and check", then the open note and its actions (Check, Copy, Share)
 * in one card. A new, blank note gets the cursor. Checks are in the app's language (Linguize
 * detects); the card is drawn below by [NoteCheckCard]. Copy and Share take the text out,
 * accepted changes included. Notes save as you type, and when you leave the page or the app.
 */
@Composable
internal fun NoteEditor(
    write: NotesViewModel,
    check: CheckViewModel,
    /** Lab, Language scope: the app's current language, which checks use; null (Linguize) detects. */
    appLanguage: String? = null,
) {
    val context = LocalContext.current
    // Set when a check was asked for with nothing in the box; cleared by typing.
    var blank by rememberSaveable { mutableStateOf(false) }

    SaveWhenLeaving(write)

    fun replace(text: String) {
        write.edit(text)
        blank = false
        // The card's suggestions are for the old text, and nothing of them was applied.
        check.dismiss(applied = false)
    }

    // Checks made here are never recorded in history (Origin.Tester isn't recorded).
    fun run() {
        val text = write.text.trim()
        if (text.isEmpty()) {
            blank = true
        } else {
            blank = false
            check.check(text, noteCheckLanguage(appLanguage), Origin.Tester)
        }
    }

    val hasText = write.text.isNotBlank()
    // A new note is for writing: the cursor goes in, and the keyboard comes up (Back closes it first).
    val focus = remember { FocusRequester() }
    val newNote = !write.loading && write.openId != null && write.text.isEmpty()
    LaunchedEffect(write.openId, newNote) { if (newNote) runCatching { focus.requestFocus() } }
    // New note is on the Notes list, which this page is opened from: here everything acts on this note.
    SectionHeader(stringResource(R.string.lab_notes_header))
    SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        LinguizeTextField(
            fieldModifier = Modifier.focusRequester(focus),
            value = write.text,
            onValueChange = { replace(it) },
            placeholder = stringResource(R.string.lab_notes_placeholder),
            accessibleLabel = stringResource(R.string.your_text),
            supporting = when {
                blank -> stringResource(R.string.no_text_to_check)
                write.showingSample -> stringResource(R.string.lab_notes_sample_hint)
                else -> null
            },
            isError = blank,
            large = true,
            minLines = 4,
        )
        // Everything that acts on this text, together and above the card.
        // Lab, Consistency: no language picker here; the one selector at the top decides.
        FlowRow(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LinguizeButton(stringResource(R.string.check), { run() }, ButtonKind.Filled)
            LinguizeButton(stringResource(R.string.lab_notes_copy), {
                context.copyToClipboard(takeFromCard(write, check))
            }, ButtonKind.Text, enabled = hasText)
            LinguizeButton(stringResource(R.string.lab_notes_share), {
                context.openExternal(shareIntent(context, takeFromCard(write, check)))
            }, ButtonKind.Text, enabled = hasText)
        }
    }
}

/**
 * The check card for the open note, under the editor card at full width. Replace puts the
 * accepted changes in the note.
 */
@Composable
internal fun NoteCheckCard(write: NotesViewModel, check: CheckViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state = check.state ?: return
    CompositionLocalProvider(LocalLanguageHint provides LanguageHint.AppSelector) {
        ResultCard(
            state = state,
            actions = CardActions.of(
                check, context, onClose = { takeFromCard(write, check) }, onOpenSettings = onOpenSettings,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            scrollable = false,
        )
    }
}

/** The tab's last lines: where notes are kept, and how to check in other apps. */
@Composable
internal fun NotesFooter() {
    Quiet(stringResource(R.string.lab_notes_kept), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
    Quiet(stringResource(R.string.lab_notes_other_apps), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
}

/** "Note deleted" with Undo, at the top of the list after a delete. */
@Composable
internal fun NoteDeletedRow(write: NotesViewModel) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.lab_notes_deleted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        LinguizeButton(stringResource(R.string.undo), write::undoDelete, ButtonKind.Text)
    }
}

/** No notes yet: what the tab is for. New note is the button under it. */
@Composable
internal fun NotesEmpty() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp)) {
        Text(stringResource(R.string.lab_notes_nav_empty), style = MaterialTheme.typography.titleMedium)
        Quiet(stringResource(R.string.lab_notes_nav_empty_hint), Modifier.padding(top = 4.dp))
    }
}

/** Every note, newest first: tap one to open its page ([onOpen]); delete with Undo. */
@Composable
internal fun NoteList(write: NotesViewModel, onOpen: (String) -> Unit) {
    val resources = LocalContext.current.resources
    ListCard(
        *write.notes.map { draft ->
            @Composable {
                ListRow(
                    draft.label(),
                    shortDate(draft.updatedAt, locale = resources.uiLocale),
                    onClick = { onOpen(draft.id) },
                    onClickLabel = stringResource(R.string.lab_notes_open),
                    trailing = {
                        IconButton(onClick = { write.delete(draft.id) }) {
                            Icon(
                                painterResource(R.drawable.lab_notes_ic_delete),
                                stringResource(R.string.lab_notes_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }.toTypedArray(),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Saves the open note when its page goes away or the app goes to the background. */
@Composable
private fun SaveWhenLeaving(write: NotesViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, write) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) write.flush() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            write.flush()
        }
    }
}

/**
 * Puts the card's accepted changes (or the version chosen in How it lands) in the draft and
 * closes the card, as Apply does; returns the draft's text. Copy and Share take this text, so
 * accepting a change and copying straight away copies the change.
 */
internal fun takeFromCard(write: NotesViewModel, check: CheckViewModel): String {
    check.workingText?.let { write.edit(Selection.of(write.text).with(it)) }
    if (check.state != null) check.dismiss()
    return write.text
}

/** The system share sheet for [text]. */
internal fun shareIntent(context: Context, text: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
    context.getString(R.string.lab_notes_share),
)
