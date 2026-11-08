package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.corpus.Utterance

/**
 * Lab Chat's part of the writer corpus (docs/lab/corpus.md): each writer message exactly as
 * typed, in its conversation's language, with the spans accepted fixes replaced marked as
 * corrected. Partner replies and sent (corrected) text never enter it. Pure.
 */
object ChatCorpus {
    const val SOURCE = "chat"

    fun utterances(conversations: List<Pair<Conversation, List<ChatMessage>>>): List<Utterance> =
        conversations.flatMap { (conversation, messages) ->
            messages.filter { it.fromWriter && it.raw.isNotBlank() }.map { message ->
                Utterance(
                    id = "$SOURCE:${message.id}",
                    source = SOURCE,
                    language = conversation.language,
                    text = message.raw,
                    corrected = message.correctedSpans,
                    at = message.at,
                )
            }
        }
}
