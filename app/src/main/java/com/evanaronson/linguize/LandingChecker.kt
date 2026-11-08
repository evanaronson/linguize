package com.evanaronson.linguize

import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.LandingPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab, "How it lands": asks the writer's model how a native reader would take a message.
 * Uses the same provider, model, key and transport as checks; keeps nothing, records nothing
 * in History and never changes the text itself.
 */
class LandingChecker(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    /** Read on first use, off the main thread. */
    prompt: () -> LandingPrompt,
) {
    private val prompt by lazy(prompt)

    suspend fun landing(text: String): Landing = withContext(Dispatchers.IO) {
        val message = text.trim()
        if (message.isEmpty() || message.length > Checker.MAX_CHARS) {
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
            val raw = clients.getValue(provider).generate(key, model, prompt.request(message, settings.nativeLanguage))
            prompt.read(raw, message)
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
