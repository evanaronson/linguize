package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.interpret
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A finished check: what the card shows, the model's answer exactly as it came ([raw]), and the settings it ran with. */
data class Checked(val result: CheckResult, val raw: String, val context: CheckContext)

/**
 * Runs checks with the provider, model and options chosen in settings.
 * Every failure is a [CheckFailure]; one about an answer that came but couldn't be used
 * carries it as [CheckFailure.raw]. Storage, key decryption and the network run off the main thread.
 */
class Checker(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val prompt: Prompt,
    private val clients: Map<Provider, ProviderClient>,
) {
    /** The settings a check started now would run with. */
    suspend fun context(): CheckContext = failingAsCheckFailure { readContext() }

    /**
     * Checks [text] as [language], or lets the model detect the language when it's null.
     * [settled] are the writer's answers to assumptions from an earlier check of the same text.
     * Runs with [context] when given, else with the settings as they are now.
     */
    suspend fun check(
        text: String,
        language: Language?,
        settled: List<Settled> = emptyList(),
        context: CheckContext? = null,
    ): Checked {
        if (text.length > MAX_CHARS) throw CheckFailure(CheckFailure.Reason.TooLong, userMessage = CheckFailure.Message.InputTooLong)
        return failingAsCheckFailure {
            val used = context ?: readContext()
            val request = CheckRequest(text, language, used.punctuation, used.judgments, settled, used.native,
                writingProfiles = used.writingProfiles)
            val client = clients.getValue(used.provider)
            val key = key(used.provider)
            val first = checked(text, language, client.check(key, used.model, request), used)
            val partial = first.result as? CheckResult.Reviewed
            if (partial == null || partial.withheld.isEmpty()) return@failingAsCheckFailure first
            val second = try {
                checked(text, language, client.check(key, used.model, request.copy(repair = true)), used)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@failingAsCheckFailure first
            }
            val revised = second.result as? CheckResult.Reviewed ?: return@failingAsCheckFailure first
            // Never splice answers or rank their linguistic quality by edit count.
            // Replace usable evidence only when the retry resolves known incompleteness.
            if (revised.withheld.size < partial.withheld.size && partial.withheld.containsAll(revised.withheld)
            ) second else first
        }
    }

    private fun checked(text: String, language: Language?, answer: ModelAnswer, context: CheckContext): Checked {
        val result = try {
            prompt.validateSize(answer.verdict)
            interpret(text, answer.verdict, context.judgments, expectedLanguage = language?.name)
        } catch (e: CheckFailure) {
            throw CheckFailure(e.reason, e.detail, e.cause ?: e, raw = answer.raw, userMessage = e.userMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = answer.raw, userMessage = CheckFailure.Message.InvalidReply)
        }
        return Checked(result, answer.raw, context)
    }

    /**
     * Shows an answer kept from an earlier check of the same [text] ([raw], as [Checked.raw])
     * again, without asking the model. Throws [CheckFailure] when it can't be read.
     */
    suspend fun reuse(text: String, language: Language?, raw: String, context: CheckContext): Checked = failingAsCheckFailure {
        try {
            val result = interpret(text, prompt.parseVerdict(raw), context.judgments, expectedLanguage = language?.name)
            Checked(result, raw, context)
        } catch (e: CheckFailure) {
            throw CheckFailure(e.reason, e.detail, e.cause ?: e, raw = raw, userMessage = e.userMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, raw = raw, userMessage = CheckFailure.Message.InvalidReply)
        }
    }

    /** Runs a short check with [model]; returns how long it took in milliseconds. */
    suspend fun testModel(provider: Provider, model: String): Long = failingAsCheckFailure {
        val started = System.nanoTime()
        clients.getValue(provider).check(key(provider), model, TEST_REQUEST)
        (System.nanoTime() - started) / 1_000_000
    }

    /** The models [provider]'s key can use, newest first. */
    suspend fun models(provider: Provider): List<String> = failingAsCheckFailure {
        clients.getValue(provider).models(key(provider))
    }

    private fun readContext(): CheckContext {
        val provider = settings.provider
        return CheckContext(
            provider = provider,
            model = settings.model(provider) ?: provider.recommendedModel,
            punctuation = settings.punctuation,
            judgments = settings.judgments,
            native = settings.nativeLanguage,
            promptHash = prompt.hash,
            writingProfiles = settings.writingProfiles.distinctBy { it.language }.sortedBy { it.language }.toList(),
        )
    }

    private fun key(provider: Provider): String {
        val key = keys.get(provider)?.takeUnless { it.isBlank() } ?: throw CheckFailure(CheckFailure.Reason.NoKey)
        // HTTP headers only carry printable ASCII; anything else can't be a real key.
        if (key.any { it !in '!'..'~' }) throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
        return key
    }

    /** Runs [block] off the main thread; anything unexpected becomes a [CheckFailure] rather than a crash. */
    private suspend fun <T> failingAsCheckFailure(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, e.message, e)
        }
    }

    companion object {
        /** About a page. Longer selections are past what this tool is for and would be slow. */
        const val MAX_CHARS = 3000

        private val TEST_REQUEST = CheckRequest("Bon dia, com estas?", null, Punctuation.Moderate, Judgments.Both)
    }
}
