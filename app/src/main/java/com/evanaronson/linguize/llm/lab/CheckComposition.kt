package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.Prompt

/**
 * Lab: one check, one request. The Lab rules are composed onto the shipped prompt in one fixed
 * order: the shipped `check_prompt.md`, then the gap rules, then the tone rules (with `landing`
 * added to the shipped schema), and, for a chat check, the reply rules last, with the
 * `Replying to:` line in front of the shipped user message ([ReplyPrompt]). The shipped assets
 * are never changed.
 */
object CheckComposition {
    /** The instructions and schema a check sends; [base] itself when there are no Lab rules. */
    fun prompt(base: Prompt, gaps: GapsInCheck?, tone: ToneInCheck?): Prompt {
        var composed = base
        if (gaps != null) composed = gaps.prompt(composed)
        if (tone != null) composed = tone.prompt(composed)
        return composed
    }

    /**
     * The context's prompt hash, so History never reuses an answer across different
     * compositions: the shipped hash, or [replyHash] for a check with a message to reply to
     * (it covers the message), then a suffix per rule set in the same order as [prompt].
     */
    fun hash(base: Prompt, gaps: GapsInCheck?, tone: ToneInCheck?, replyHash: String? = null): String {
        var hash = replyHash ?: base.hash
        if (gaps != null) hash = gaps.hash(hash)
        if (tone != null) hash = tone.hash(hash)
        return hash
    }
}
