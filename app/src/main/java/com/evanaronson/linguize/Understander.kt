package com.evanaronson.linguize

import com.evanaronson.linguize.core.Understood
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.UnderstandPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reading assistance deliberately has no history, revision, or write-back dependency. */
class Understander(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    private val prompt: UnderstandPrompt = UnderstandPrompt(),
) {
    suspend fun understand(text: String): Understood = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length > Checker.MAX_CHARS) {
            throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        }
        try {
            val provider = settings.provider
            val key = keys.get(provider)?.takeUnless { it.isBlank() }
                ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val request = prompt.request(text, settings.nativeLanguage)
            val raw = clients.getValue(provider).generate(
                key, settings.model(provider) ?: provider.recommendedModel, request,
            )
            prompt.read(raw)
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
