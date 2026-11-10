package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.Prompt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Lab: a check of a reply, with the message it answers as context.
 *
 * The shipped [Prompt] is never changed. Only when a message is attached, this composes a
 * request from it: the shipped instructions followed by [addendum] (lab_reply_context_prompt.md),
 * and a `Replying to:` line in front of the shipped user message, which follows unchanged.
 * The answer uses the shipped schema and is read by the shipped parser, so it is a normal
 * check answer everywhere else: the card, history and replay.
 */
class ReplyPrompt(val addendum: String) {
    /** The shipped instructions, then the reply rules. */
    fun system(baseSystem: String): String = baseSystem.trimEnd() + "\n\n" + addendum.trim() + "\n"

    /**
     * One `Replying to:` JSON line, then [Prompt.userMessage] exactly as a check without context
     * sends it. The message is JSON on one line, so a line break or tag in it can't forge a
     * setting or the text; the writer's text stays last, as the shipped prompt requires.
     */
    fun userMessage(base: Prompt, request: CheckRequest, message: String): String =
        "$LINE_PREFIX${messageJson(message)}\n" + base.userMessage(request)

    /**
     * Stands in for [Prompt.hash] in a check with context. It differs from the shipped hash and
     * for each message, so history never reuses an answer across contexts (or with none) and
     * never stores the message itself.
     */
    fun hash(base: Prompt, message: String): String = base.hash + "-r" + MessageDigest.getInstance("SHA-256")
        .digest("${base.hash}\u0000$addendum\u0000$message".toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val ASSET = "lab_reply_context_prompt.md"
        const val LINE_PREFIX = "Replying to: "

        /** The same bound as the text: a message, not a thread. */
        const val MAX_CHARS = 3000

        /** The message as it is sent: trimmed; null when blank or longer than [MAX_CHARS]. */
        fun normalize(message: String?): String? =
            message?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }

        /** `{"message": …}` on one line, Unicode line separators escaped as well. */
        internal fun messageJson(message: String): String = buildJsonObject { put("message", message) }
            .toString()
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")

        /** Reads [ASSET] with [read] (the app's assets). */
        fun load(read: (String) -> String) = ReplyPrompt(read(ASSET))
    }
}
