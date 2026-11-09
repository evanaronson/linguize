package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.ProviderClient

/**
 * Lab: [delegate] for one check of a reply to [message]. Its checks (the first request and any
 * repair retry) go through [ReplyPrompt]; everything else is the delegate's own.
 */
class ReplyingClient(
    private val delegate: ProviderClient,
    private val reply: ReplyPrompt,
    private val base: Prompt,
    private val message: String,
    /** Whose contract reads the answer; [base] unless Lab fields are composed onto its schema. */
    private val reader: Prompt = base,
) : ProviderClient {
    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer =
        reply.check(delegate, base, key, model, request, message, reader)

    override suspend fun generate(key: String, model: String, request: GenerationRequest): String =
        delegate.generate(key, model, request)

    override suspend fun models(key: String): List<String> = delegate.models(key)
}
