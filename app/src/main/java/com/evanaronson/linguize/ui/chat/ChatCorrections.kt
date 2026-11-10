package com.evanaronson.linguize.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.ChatLabels
import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.core.chat.SentCheck
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.SuggestionRecord
import com.evanaronson.linguize.ui.history.SavedBefore
import com.evanaronson.linguize.ui.history.SavedSuggestions
import com.evanaronson.linguize.ui.phrasebook.PhraseOrigin
import com.evanaronson.linguize.ui.phrasebook.ProvidePhraseOrigin
import com.evanaronson.linguize.ui.teachme.LessonSource
import com.evanaronson.linguize.ui.teachme.LocalLessonSource

/**
 * Lab, Chat corrections: a sent message's accepted changes as the rows History shows for a
 * saved check. Each change was accepted, so each is Accepted; the sent text exists, so they
 * read "Applied". Row ids are the message's id and the change's place, stable for the
 * disclosure state. An adapter only: the chat draws no corrections UI of its own.
 */
internal fun chatSuggestionRows(message: ChatMessage): List<SuggestionRecord> =
    message.changes.mapIndexed { index, change ->
        SuggestionRecord(
            id = "${message.id}:$index",
            sessionId = message.sessionId ?: message.id,
            deviceId = "",
            createdAt = message.at,
            updatedAt = message.at,
            attempt = 0,
            kind = change.kind,
            start = change.start,
            end = change.end,
            fromText = change.from,
            toText = change.to,
            why = change.why,
            decision = Decision.Accepted,
            decidedAt = message.at,
        )
    }

/** Whether a tap on the writer's bubble opens anything: only a message sent with changes. */
internal fun hasCorrections(message: ChatMessage): Boolean =
    message.fromWriter && message.check == SentCheck.Corrected && message.changes.isNotEmpty()

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
 * Under a corrected bubble, opened by tapping it: History's own "Before" and "Suggestions" for
 * the message, as a saved check shows them (the bubble above is the "After"). Each change opens
 * its reason in place, with Teach me and the Phrasebook star, in the conversation's [language].
 */
@Composable
internal fun ChatCorrections(message: ChatMessage, language: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        SavedBefore(message.raw)
        CompositionLocalProvider(LocalLessonSource provides LessonSource(language, message.raw)) {
            ProvidePhraseOrigin(PhraseOrigin(language = { language }, sessionId = { message.sessionId })) {
                SavedSuggestions(message.id, chatSuggestionRows(message), hasFinalText = true)
            }
        }
    }
}
