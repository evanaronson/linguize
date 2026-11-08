package com.evanaronson.linguize

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.lab.GapFill
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.lab.GapPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The settings a fill runs with: never credentials. */
data class GapContext(
    val provider: Provider,
    val model: String,
    val native: String,
    val profiles: List<WritingProfile>,
)

/**
 * Fills the gaps in a message with the provider, model and writing profiles chosen in
 * settings. Like reading help, it has no history or write-back of its own: the host decides
 * what to do with the result. Every failure is a [CheckFailure].
 */
class GapFiller(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    /** Called on first use, off the main thread (it may read assets). */
    prompt: () -> GapPrompt,
) {
    private val gaps by lazy(prompt)

    suspend fun context(): GapContext = withContext(Dispatchers.IO) {
        val provider = settings.provider
        GapContext(
            provider = provider,
            model = settings.model(provider) ?: provider.recommendedModel,
            native = settings.nativeLanguage,
            profiles = settings.writingProfiles.distinctBy { it.language }.sortedBy { it.language },
        )
    }

    /** Fills [text] in [language], or in the language its own words are in when null. */
    suspend fun fill(text: String, language: Language?, requested: GapContext? = null): GapFill = withContext(Dispatchers.IO) {
        if (text.isBlank() || text.length > Checker.MAX_CHARS) {
            throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        }
        try {
            val used = requested ?: context()
            val key = keys.get(used.provider)?.takeUnless { it.isBlank() }
                ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val raw = clients.getValue(used.provider).generate(
                key, used.model, gaps.request(text, language, used.native, used.profiles),
            )
            gaps.read(raw, text)
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
