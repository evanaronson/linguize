package com.evanaronson.linguize

import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.chat.ChatPrompt
import com.evanaronson.linguize.llm.chat.ChatSetting
import com.evanaronson.linguize.llm.chat.PartnerReply
import com.evanaronson.linguize.llm.chat.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lab Chat: the conversation partner, through the same provider clients and key as checks.
 * It reads what [memory] holds about the writer for its instructions; it has no history,
 * revision or write-back dependency.
 */
class ChatPartner(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    private val memory: () -> UserMemory,
    /** The practice targets block for a language ([ChatTargets]); "" for none. */
    private val targets: suspend (Language) -> String = { "" },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Read on first use, off the main thread. */
    prompt: () -> ChatPrompt,
) {
    private val prompt by lazy(prompt)

    /** Whether the provider in settings has a key. */
    fun hasKey(): Boolean = !keys.get(settings.provider).isNullOrBlank()

    /** A new conversation's opener in [language]; with [persona], that person's first message. Throws [CheckFailure]. */
    suspend fun open(language: Language, persona: String? = null): PartnerReply =
        ask(language, persona) { setting -> prompt.openRequest(setting) }

    /** The answer to the last turn of [history] in [language]; with [needTitle], a title too. Throws [CheckFailure]. */
    suspend fun reply(language: Language, history: List<Turn>, needTitle: Boolean, persona: String? = null): PartnerReply =
        ask(language, persona) { setting -> prompt.replyRequest(setting, history, needTitle) }

    private suspend fun ask(language: Language, persona: String?, request: (ChatSetting) -> GenerationRequest): PartnerReply =
        withContext(dispatcher) {
            try {
                val provider = settings.provider
                val model = settings.model(provider) ?: provider.recommendedModel
                val key = keys.get(provider)?.takeUnless { it.isBlank() } ?: throw CheckFailure(CheckFailure.Reason.NoKey)
                if (key.any { it !in '!'..'~' }) {
                    throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
                }
                val notes = try {
                    memory().forPrompt()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                val variety = settings.writingProfiles.firstOrNull { it.language == language.code }?.variety
                    ?.takeIf { it.isNotBlank() } ?: language.variety.orEmpty()
                val steer = try {
                    targets(language)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                val setting = ChatSetting(language.name, variety, settings.nativeLanguage, notes, steer, persona?.takeIf { it.isNotBlank() })
                prompt.read(clients.getValue(provider).generate(key, model, request(setting)))
            } catch (e: CheckFailure) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
            }
        }
}
