package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.PreparedCheck
import com.evanaronson.linguize.llm.Prompt

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
        val replyRules = replyingTo?.let { requireNotNull(reply) { "A reply check needs reply rules" } }
        var system = base.system
        if (gaps != null) system = gaps.system(system)
        if (tone != null) system = tone.system(system)
        if (replyRules != null) system = replyRules.system(system)
        return PreparedCheck(
            generation = GenerationRequest(
                system = system,
                user = replyRules?.userMessage(base, request, replyingTo) ?: base.userMessage(request),
                schema = tone?.schema(base.schema) ?: base.schema,
                name = "check",
                maxTokens = Prompt.MAX_OUTPUT_TOKENS,
            ),
            compatibilityHash = hash(base, gaps, tone, replyRules?.hash(base, replyingTo)),
            baseReader = base,
        )
    }

    /**
     * The context's prompt hash, so History never reuses an answer across different
     * compositions: the shipped hash, or [replyHash] for a check with a message to reply to
     * (it covers the message), then a suffix per rule set in the same order as [prepare].
     */
    fun hash(base: Prompt, gaps: GapsInCheck?, tone: ToneInCheck?, replyHash: String? = null): String {
        var hash = replyHash ?: base.hash
        if (gaps != null) hash = gaps.hash(hash)
        if (tone != null) hash = tone.hash(hash)
        return hash
    }
}
