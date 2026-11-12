package com.evanaronson.linguize.ui.chat

import com.evanaronson.linguize.core.chat.ChatMessage
import com.evanaronson.linguize.llm.lab.ReplyPrompt

/**
 * Lab: what a chat check replies to (docs/lab/reply-context.md). Every message checked in the
 * chat goes with the partner's most recent message before it, so the check can see the form of
 * address, what was asked and what pronouns refer to. Context only: it is never shown on the
 * card, and History and the corpus keep only the writer's text.
 */
object ChatReplyContext {
    /** The partner's last message in [messages] (oldest first), trimmed; null when there is none. */
    fun of(messages: List<ChatMessage>): String? {
        val text = messages.lastOrNull { !it.fromWriter }?.text?.trim() ?: return null
        // A reply longer than a message's bound keeps its end, which is what the draft answers.
        return ReplyPrompt.normalize(text.takeLast(ReplyPrompt.MAX_CHARS))
    }
}
