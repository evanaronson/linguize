package com.evanaronson.linguize

import com.evanaronson.linguize.core.reply.ReplyDraft
import com.evanaronson.linguize.core.reply.ReplyIntents
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.reply.ReplyInput
import com.evanaronson.linguize.llm.reply.ReplyPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab: drafts a reply to a message the writer received, through the same provider clients as
 * checks. Like [Understander], it has no history, revision or write-back dependency.
 */
class ReplyDrafter(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    /** Read on first use, off the main thread. */
    prompt: () -> ReplyPrompt,
) {
    private val prompt by lazy(prompt)

    /** Ways to answer [message]; [translation] is what the writer read. Throws [CheckFailure]. */
    suspend fun intents(message: String, translation: String): ReplyIntents =
        ask(message, translation) { input, send -> prompt.readIntents(send(prompt.intentsRequest(input))) }

    /**
     * A reply to [message] that says what [intent] describes; with [simplerThan], a simpler
     * version of that earlier draft. Throws [CheckFailure].
     */
    suspend fun draft(message: String, translation: String, intent: String, simplerThan: String? = null): ReplyDraft {
        if (intent.isBlank() || intent.length > ReplyPrompt.MAX_INTENT_INPUT) {
            throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        }
        return ask(message, translation) { input, send -> prompt.readReply(send(prompt.replyRequest(input, intent.trim(), simplerThan))) }
    }

    private suspend fun <T> ask(
        message: String,
        translation: String,
        request: suspend (input: ReplyInput, send: suspend (GenerationRequest) -> String) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (message.isBlank() || message.length > Checker.MAX_CHARS) {
            throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        }
        try {
            val provider = settings.provider
            val model = settings.model(provider) ?: provider.recommendedModel
            val key = keys.get(provider)?.takeUnless { it.isBlank() }
                ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val input = ReplyInput(message.trim(), translation.trim(), settings.nativeLanguage, settings.writingProfiles)
            val client = clients.getValue(provider)
            request(input) { client.generate(key, model, it) }
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
