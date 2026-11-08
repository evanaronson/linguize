package com.evanaronson.linguize.ui.photo

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.llm.lab.PhotoReading
import com.evanaronson.linguize.ui.card.Failure
import com.evanaronson.linguize.ui.card.Outcome
import com.evanaronson.linguize.ui.card.Progress
import com.evanaronson.linguize.ui.components.ButtonKind
import com.evanaronson.linguize.ui.components.CardNote
import com.evanaronson.linguize.ui.components.LinguizeButton
import com.evanaronson.linguize.ui.components.Quiet
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.ScreenColumn
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.components.SectionHeader
import com.evanaronson.linguize.ui.copyToClipboard

/** Opens [uri] through the content resolver, shrinks it in memory and sends it. */
fun PhotoViewModel.understand(context: Context, uri: Uri) {
    val resolver = context.applicationContext.contentResolver
    understand { PhotoImage.prepare(resolver, uri) }
}

/**
 * The system photo picker, images only. It needs no storage permission: the person picks
 * one image and the app gets that image alone. Returns the function that opens it.
 */
@Composable
fun rememberPhotoPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPicked)
    }
    return {
        try {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            // Every supported Android version has a picker; a broken device just does nothing.
        }
    }
}

/** A photo's reading, as an app screen: what it is, what to do, the translation, the text and its words. */
@Composable
fun PhotoScreen(photo: PhotoViewModel, onOpenSettings: (() -> Unit)?) {
    val context = LocalContext.current
    val pick = rememberPhotoPicker { photo.understand(context, it) }
    ScreenColumn {
        when (val state = photo.state) {
            PhotoState.Idle -> StatusCard {
                Outcome(stringResource(R.string.lab_share_photo_idle), stringResource(R.string.lab_share_photo_idle_caption))
                ChooseButton(R.string.lab_share_photo_choose, ButtonKind.Filled, pick)
            }
            PhotoState.Preparing -> StatusCard { Working(R.string.lab_share_photo_preparing) }
            PhotoState.Reading -> StatusCard { Working(R.string.lab_share_photo_reading) }
            PhotoState.CantOpen -> StatusCard {
                Outcome(stringResource(R.string.lab_share_photo_cant_open), stringResource(R.string.lab_share_photo_cant_open_hint))
                ChooseButton(R.string.lab_share_photo_choose_another, ButtonKind.Filled, pick)
            }
            PhotoState.TooMany -> StatusCard {
                Outcome(stringResource(R.string.lab_share_photo_too_many), stringResource(R.string.lab_share_photo_too_many_hint))
                ChooseButton(R.string.lab_share_photo_choose, ButtonKind.Outlined, pick)
            }
            is PhotoState.Failed -> StatusCard {
                Failure(state.failure.reason, state.failure.userMessage, state.failure.detail, photo::retry, onOpenSettings)
                ChooseButton(R.string.lab_share_photo_choose_another, ButtonKind.Text, pick)
            }
            is PhotoState.Done -> if (state.reading.legible) {
                Reading(state.reading)
                Spacer(Modifier.height(12.dp))
                CardNote(stringResource(R.string.lab_share_photo_double_check))
                ChooseButton(R.string.lab_share_photo_choose_another, ButtonKind.Outlined, pick, Modifier.padding(start = 12.dp))
            } else {
                StatusCard {
                    Outcome(stringResource(R.string.lab_share_photo_unreadable), stringResource(R.string.lab_share_photo_unreadable_hint))
                    ChooseButton(R.string.lab_share_photo_choose_another, ButtonKind.Filled, pick)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Reading(reading: PhotoReading) {
    val context = LocalContext.current
    if (reading.summary.isNotEmpty() || reading.language.isNotEmpty()) {
        SectionHeader(stringResource(R.string.lab_share_photo_what_it_is))
        Card {
            // Announced when the reading arrives, like the card's outcome.
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (reading.summary.isNotEmpty()) SelectionContainer { Text(reading.summary, style = MaterialTheme.typography.bodyLarge) }
                if (reading.language.isNotEmpty()) Quiet(stringResource(R.string.lab_share_photo_written_in, reading.language))
            }
        }
    }

    SectionHeader(stringResource(R.string.lab_share_photo_what_to_do))
    Card {
        if (reading.actions.isEmpty()) Quiet(stringResource(R.string.lab_share_photo_no_actions))
        reading.actions.forEachIndexed { index, action ->
            if (index > 0) {
                Spacer(Modifier.height(10.dp))
                RowDivider()
                Spacer(Modifier.height(10.dp))
            }
            SelectionContainer {
                Column {
                    Text(action.task, style = MaterialTheme.typography.titleSmall)
                    if (action.deadline.isNotEmpty()) {
                        Text(stringResource(R.string.lab_share_photo_deadline, action.deadline), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (action.amount.isNotEmpty()) {
                        Text(stringResource(R.string.lab_share_photo_amount, action.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.lab_share_photo_translation))
    Card {
        SelectionContainer { Text(reading.translation, style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(8.dp))
        LinguizeButton(stringResource(R.string.lab_share_photo_copy_translation), {
            context.copyToClipboard(reading.translation)
        }, ButtonKind.Tonal)
    }

    SectionHeader(stringResource(R.string.lab_share_photo_original))
    Card {
        SelectionContainer {
            Text(reading.transcription, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        LinguizeButton(stringResource(R.string.lab_share_photo_copy_original), {
            context.copyToClipboard(reading.transcription)
        }, ButtonKind.Tonal)
    }

    if (reading.words.isNotEmpty()) {
        SectionHeader(stringResource(R.string.lab_share_photo_words))
        Card {
            reading.words.forEachIndexed { index, word ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Column {
                        Text(word.word, style = MaterialTheme.typography.labelLarge)
                        Text(word.explanation, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    SectionCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), content = content)
}

@Composable
private fun StatusCard(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(8.dp))
    Card(content)
}

@Composable
private fun Working(label: Int) {
    Quiet(stringResource(label))
    Spacer(Modifier.height(14.dp))
    Progress(stringResource(label))
}

@Composable
private fun ChooseButton(label: Int, kind: ButtonKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(12.dp))
    LinguizeButton(stringResource(label), onClick, kind, modifier)
}
