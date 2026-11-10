package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.PreparedCheck
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.generation

/**
 * Lab: one check, one request. The Lab rules are composed onto the shipped prompt in one fixed
 * order: the shipped `check_prompt.md`, then the gap rules, then the tone rules (with `landing`
 * added to the shipped schema), and, for a chat check, the reply rules last, with the
 * `Replying to:` line in front of the shipped user message ([ReplyPrompt]). The shipped assets
 * are never changed.
 */
object CheckComposition {
    /** Request, compatible History identity and reader prepared from the same rule sets. */
    fun prepare(
        base: Prompt,
        gaps: GapsInCheck?,
        tone: ToneInCheck?,
        request: CheckRequest,
        reply: ReplyPrompt? = null,
        replyingTo: String? = null,
    ): PreparedCheck {
        val composed = prompt(base, gaps, tone)
        val replyHash: String?
        val generation = if (replyingTo != null) {
            val replyRules = requireNotNull(reply) { "A reply check needs reply rules" }
            replyHash = replyRules.hash(base, replyingTo)
            replyRules.generation(composed, request, replyingTo)
        } else {
            replyHash = null
            composed.generation(request)
        }
        return PreparedCheck(
            generation = generation,
            compatibilityHash = hash(base, gaps, tone, replyHash),
            baseReader = base,
        )
    }

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
