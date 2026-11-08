package com.evanaronson.linguize.core.reply

import kotlinx.serialization.Serializable

/** A way the writer might answer, proposed for this message: a short [label] and what the reply would say. */
@Serializable
data class ReplyIntent(val label: String, val instruction: String)

/** The model's proposals for one incoming message, and the language it was written in. */
@Serializable
data class ReplyIntents(val language: String, val intents: List<ReplyIntent>)

/** One sentence of a drafted reply, as it would be sent, with its gloss in the writer's language. */
@Serializable
data class ReplySentence(val text: String, val gloss: String, val lineBreak: Boolean)

/** A word or short phrase from the reply worth learning, with its sense here. */
@Serializable
data class ReplyWord(val word: String, val meaning: String)

/**
 * A reply drafted for the writer in the incoming message's [language]. The sentences carry the
 * text; [text] joins them, so the gloss always lines up with what is shown.
 */
@Serializable
data class ReplyDraft(val language: String, val sentences: List<ReplySentence>, val words: List<ReplyWord>) {
    val text: String
        get() = buildString {
            sentences.forEachIndexed { index, sentence ->
                if (index > 0) append(if (sentences[index - 1].lineBreak) "\n" else " ")
                append(sentence.text)
            }
        }
}
