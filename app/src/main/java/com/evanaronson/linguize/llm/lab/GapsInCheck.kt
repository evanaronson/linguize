package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.generation
import com.evanaronson.linguize.llm.read
import java.security.MessageDigest

/**
 * Lab: gap filling as part of every check (docs/lab/gaps-in-check.md).
 *
 * The shipped [Prompt] is never changed. This composes the instructions a check sends: the
 * shipped `check_prompt.md`, then [addendum] (`lab_gaps_in_check.md`). The user message and
 * the schema are the shipped ones, so the answer is read by the shipped parser and a filled
 * gap is an ordinary fix to the card, the edit engine and History.
 */
class GapsInCheck(val addendum: String) {
    /** The shipped instructions, then the gap rules. */
    fun system(base: Prompt): String = base.system.trimEnd() + "\n\n" + addendum.trim() + "\n"

    /** [base] with the gap rules added: the same schema and user message. */
    fun prompt(base: Prompt): Prompt = Prompt(system(base), base.schema.toString())

    /**
     * The context's prompt hash: [baseHash] (the shipped hash, or a reply check's) with a
     * suffix for these rules, so History never reuses an answer from a check without them.
     */
    fun hash(baseHash: String): String = baseHash + "-g" + MessageDigest.getInstance("SHA-256")
        .digest(addendum.toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val ASSET = "lab_gaps_in_check.md"

        /** Reads [ASSET] with [read] (the app's assets). */
        fun load(read: (String) -> String) = GapsInCheck(read(ASSET))
    }
}

/**
 * Lab: [delegate] with its checks sent as [prompt] (the composed instructions) over the
 * delegate's own transport; everything else is the delegate's.
 */
class ComposedPromptClient(private val delegate: ProviderClient, private val prompt: Prompt) : ProviderClient {
    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer =
        prompt.read(delegate.generate(key, model, prompt.generation(request)))

    override suspend fun generate(key: String, model: String, request: GenerationRequest): String =
        delegate.generate(key, model, request)

    override suspend fun models(key: String): List<String> = delegate.models(key)
}
