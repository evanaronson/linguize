package com.evanaronson.linguize

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.languagebridge.Bridged
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.LanguageBridgePrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Says the writer's message in another of their profile languages ("Say it in Catalan").
 * Like reading help, it has no history, revision or write-back dependency: hosts decide
 * what to do with the text, and a check of it goes through [Checker] as usual.
 */
class LanguageBridge(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    /** Called on first use, off the main thread (it reads assets). */
    prompt: () -> LanguageBridgePrompt,
) {
    private val prompt by lazy(prompt)

    /** The writer's profile languages, one profile each, in settings order. */
    suspend fun targets(): List<WritingProfile> = withContext(Dispatchers.IO) {
        settings.writingProfiles.distinctBy { it.language }
    }

    /**
     * [text] in the profile language [target] (an ISO code). [sourceHint] is the language
     * the writer pinned, when they did. Throws [CheckFailure].
     */
    suspend fun bridge(text: String, target: String, sourceHint: String? = null): Bridged = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length > Checker.MAX_CHARS) {
            throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        }
        try {
            val provider = settings.provider
            val model = settings.model(provider) ?: provider.recommendedModel
            val profile = settings.writingProfiles.firstOrNull { it.language == target }
                ?: Language.forCode(target)?.let { WritingProfile.defaultFor(it, settings.punctuation) }
                ?: throw CheckFailure(CheckFailure.Reason.BadResponse, "Unknown target language")
            val key = keys.get(provider)?.takeUnless { it.isBlank() }
                ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val request = prompt.request(text, profile, sourceHint?.takeIf { it != target }, settings.nativeLanguage)
            prompt.read(clients.getValue(provider).generate(key, model, request), text)
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
