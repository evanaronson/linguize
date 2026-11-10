package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.corpus.Span

/** Lab Chat: one conversation with the practice partner. [language] is an ISO 639-1 code. */
data class Conversation(
    val id: String,
    /** The writer's own title, or one derived from the first exchange; "" until there is one. */
    val title: String,
    val language: String,
    val createdAt: Long,
    /** When the last message was added; [createdAt] before any. */
    val updatedAt: Long,
    /** Whether the writer named it, so a derived title never replaces theirs. */
    val titleIsCustom: Boolean = false,
    /** How many messages user memory has learned from; learning runs again once there are more. */
    val learnedCount: Int = 0,
    /**
     * Who the partner plays, when the writer is practising with a stand-in for someone real
     * ("Jordi, my landlord, formal"); null for the usual friendly partner.
     */
    val persona: String? = null,
)

/** How a writer's message was checked before it was sent. */
enum class SentCheck(val token: String) {
    /** A partner message, or a writer message sent without any check. */
    None("none"),

    /** The check found nothing to change: sent as typed. */
    LooksGood("looks_good"),

    /** The writer accepted at least one change. */
    Corrected("corrected"),

    /** A check ran (or failed), and the writer sent their own text anyway. */
    AsWritten("as_written"),
    ;

    companion object {
        fun read(token: String?) = entries.firstOrNull { it.token == token } ?: None
    }
}

/**
 * One accepted change of a writer's message, anchored to the text as typed:
 * `raw.substring(start, end) == from`, and the sent text has [to] there instead.
 */
data class ChatChange(
    val kind: EditKind,
    val start: Int,
    val end: Int,
    val from: String,
    val to: String,
    val why: String? = null,
) {
    init {
        require(start in 0..end) { "A change starts at or before its end" }
    }

    val span get() = Span(start, end)
}

/**
 * One message. For the writer, [raw] is exactly what they typed and [text] what was sent
 * (the same unless they accepted changes); for the partner both are the reply.
 */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val fromWriter: Boolean,
    val raw: String,
    val text: String,
    val changes: List<ChatChange> = emptyList(),
    val check: SentCheck = SentCheck.None,
    val at: Long,
    /** The History session the message was saved as (Origin.Chat); null when History was off or it wasn't checked. */
    val sessionId: String? = null,
    /**
     * A partner message in the writer's own language, written with it in the same answer;
     * null for the writer's messages and for replies saved before translations came with them.
     * Model output: never in History or the corpus.
     */
    val translation: String? = null,
) {
    init {
        require(changes.all { it.end <= raw.length && raw.substring(it.start, it.end) == it.from }) {
            "Each change must hold its text in the raw message"
        }
    }

    /** The spans of [raw] an accepted fix replaced: what the writer tried and got wrong. */
    val correctedSpans: List<Span> get() = changes.filter { it.kind == EditKind.Fix }.map { it.span }

    companion object {
        /** A partner reply, or the opener. */
        fun partner(id: String, conversationId: String, text: String, at: Long, translation: String? = null) =
            ChatMessage(id, conversationId, fromWriter = false, raw = text, text = text, at = at,
                translation = translation?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/** What the writer is about to send: the record [ChatMessage] keeps for one writer message. */
data class Outgoing(
    val raw: String,
    val text: String,
    val changes: List<ChatChange>,
    val check: SentCheck,
) {
    fun message(id: String, conversationId: String, at: Long, sessionId: String? = null) =
        ChatMessage(id, conversationId, fromWriter = true, raw = raw, text = text, changes = changes, check = check, at = at,
            sessionId = sessionId)
}

/**
 * Lab Chat in History: the conversation a saved chat message was sent in, for its row label
 * ("Chat · title") and "Open conversation". [messages] counts what the writer sent, [corrected] those sent with changes.
 */
data class ChatThreadInfo(
    val conversationId: String,
    val title: String,
    val persona: String?,
    val language: String,
    val messages: Int,
    val corrected: Int,
)

/** A row of the conversation list. */
data class ConversationSummary(
    val conversation: Conversation,
    /** The latest message's text, or null when there is none. */
    val lastText: String?,
    val writerMessages: Int,
    /** Whether the latest message is the writer's (shown "You: …"), so the list says whose turn it is. */
    val lastFromWriter: Boolean = false,
)

/** A title from the first thing the writer said: its first line, cut at a word near [maxChars]. */
fun deriveTitle(firstWriterText: String, maxChars: Int = 40): String {
    val line = firstWriterText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    if (line.length <= maxChars) return line
    val cut = line.take(maxChars)
    val space = cut.lastIndexOf(' ')
    return (if (space >= maxChars / 2) cut.take(space) else cut).trimEnd(' ', ',', ';', ':') + "…"
}

/**
 * What the new-conversation sheet starts: who the writer is talking to, when they said
 * (the partner then plays them), and the language picked, if any. A persona is just an
 * optional property of a conversation; empty means the usual partner.
 */
data class NewConversation(val persona: String?, val language: String?) {
    companion object {
        /** The longest description of who the writer is talking to. */
        const val MAX_PERSONA = 300

        fun of(who: String, language: String?) = NewConversation(
            persona = who.trim().replace(Regex("\\s+"), " ").take(MAX_PERSONA).takeIf { it.isNotEmpty() },
            language = language?.takeIf { it.isNotBlank() },
        )
    }
}
