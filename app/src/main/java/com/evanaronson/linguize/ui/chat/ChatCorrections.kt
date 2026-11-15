package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatLabels
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.ui.card.ChangeRecord
import com.evanaronson.linguize.ui.card.Highlight
import com.evanaronson.linguize.ui.card.RecordPassage
import com.evanaronson.linguize.ui.card.RecordedChange
import com.evanaronson.linguize.ui.card.recordPassages
import com.evanaronson.linguize.ui.components.RowDivider
import com.evanaronson.linguize.ui.components.SectionCard
import com.evanaronson.linguize.ui.phrasebook.PhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.ProvidePhraseOrigin
import com.evanaronson.linguize.ui.teachme.LessonSource
import com.evanaronson.linguize.ui.teachme.LocalLessonSource
import com.evanaronson.linguize.ui.time.messageTimeLine

/** Marks the block a corrected bubble opens, for tests of its size. */
internal const val CHANGES_TAG = "lab_chat_changes"

/** Whether a tap on the writer's bubble opens anything: only a message sent with changes. */
internal fun hasCorrections(message: ChatMessage): Boolean =
    message.fromWriter && message.check == SentCheck.Corrected && message.changes.isNotEmpty()

/**
 * What the corrections block shows for [message]: the card's change view of what was typed,
 * each accepted change written in place (old words struck through, then the new). A message
 * whose changes overlap (a fix, then the rewording that replaced it) shows the card's two texts.
 * Changes that no longer fit the typed text are left out; failing everything, the sent text.
 * [none] stands in for a change saved without a reason.
 */
internal fun chatPassages(message: ChatMessage, none: String?): List<RecordPassage> {
    val changes = message.changes.map {
        RecordedChange(it.kind, it.start, it.end, it.from, it.to, it.why?.takeIf { why -> why.isNotBlank() } ?: none)
    }
    return recordPassages(message.raw, changes, dropUnfit = true)
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(RecordPassage(null, message.text, emptyList<Highlight>()))
}

/**
 * The quiet line at the bottom end of the writer's bubble: how it was checked ("Looks good",
 * "Sent as written", "Corrected, 2 changes" or "Reworded, 2 changes"); null for no check.
 */
@Composable
internal fun checkStatus(message: ChatMessage): String? = when (message.check) {
    SentCheck.None -> null
    SentCheck.LooksGood -> stringResource(R.string.lab_chat_looks_good)
    SentCheck.AsWritten -> stringResource(R.string.lab_chat_as_written)
    SentCheck.Corrected -> {
        val count = message.changes.size
        // Lab, Chat tab IA: "Corrected" only when a fix was accepted; rewordings alone weren't mistakes.
        when (ChatLabels.sent(message.changes)) {
            ChatLabels.Sent.Corrected -> pluralStringResource(R.plurals.lab_chat_changes_count, count, count)
            ChatLabels.Sent.Reworded -> pluralStringResource(R.plurals.lab_ia_chat_reworded_count, count, count)
        }
    }
}

/**
 * Under a corrected bubble, opened by tapping it: the message as the check card showed it,
 * read-only ([ChangeRecord], the card's own change view). What was typed, with each accepted
 * change written in place: the old words struck through, then the new ones, underlined solid
 * proof for a fix and dotted for a rewording. Tapping a change opens its explanation under
 * the text, the card's panel (the reason, Teach me in the conversation's [language], the
 * Phrasebook star), one at a time, with nothing to accept. No list of every change. The
 * message's time ends the block, quietly.
 */
@Composable
internal fun ChatCorrections(message: ChatMessage, language: String?, modifier: Modifier = Modifier) {
    val none = stringResource(R.string.history_no_explanation)
    val passages = remember(message.raw, message.text, message.changes, none) { chatPassages(message, none) }
    SectionCard(
        modifier.padding(top = 4.dp, bottom = 8.dp).semantics { testTag = CHANGES_TAG },
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        CompositionLocalProvider(LocalLessonSource provides LessonSource(language, message.raw)) {
            ProvidePhraseOrigin(PhraseOrigin(language = { language }, sessionId = { message.sessionId })) {
                // Screen readers hear what was typed, and reach each change's reason as an action, as on the card.
                ChangeRecord(passages, spoken = stringResource(R.string.lab_chat_changes_you_wrote, message.raw))
            }
        }
        // Lab, Time spent: the message's time, last and quiet; nothing when none was recorded.
        messageTimeLine(message.sessionId)?.let { time ->
            Spacer(Modifier.height(10.dp))
            RowDivider()
            MessageTimeText(time, Modifier.padding(top = 8.dp))
        }
    }
}

/** Marks a message's time line, for tests. */
internal const val MESSAGE_TIME_TAG = "lab_chat_message_time"

/** One message's time ([messageTimeLine]), in quiet type. */
@Composable
internal fun MessageTimeText(time: String, modifier: Modifier = Modifier) {
    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { testTag = MESSAGE_TIME_TAG })
}
