package com.evanaronson.linguize

import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.teachme.LessonCache
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.teachme.LessonPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Teach me": a one-minute lesson for one decision of a check, in the writer's own language.
 * A lesson made before opens from [cache] without the network; otherwise it asks the
 * provider and model chosen in settings, with [prompt]'s own contract, and saves the answer.
 * Like [Understander], it has no history, revision or write-back dependency.
 */
class Teacher(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    private val cache: LessonCache,
    prompt: () -> LessonPrompt,
) {
    /** Read from assets on the first lesson that needs the model, off the main thread. */
    private val contract by lazy(prompt)

    /** The saved lesson for [topic], or null. Never touches the network. */
    suspend fun saved(topic: LessonTopic): Lesson? = withContext(Dispatchers.IO) {
        runCatching { cache.get(topic, settings.nativeLanguage) }.getOrNull()
    }

    /** The saved lesson for [topic], or a new one. Throws [CheckFailure]. */
    suspend fun teach(topic: LessonTopic): Lesson = withContext(Dispatchers.IO) {
        if (!topic.teachable) throw CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)
        val native = settings.nativeLanguage
        runCatching { cache.get(topic, native) }.getOrNull()?.let { return@withContext it }
        try {
            val provider = settings.provider
            val model = settings.model(provider) ?: provider.recommendedModel
            val key = keys.get(provider)?.takeUnless { it.isBlank() }
                ?: throw CheckFailure(CheckFailure.Reason.NoKey)
            if (key.any { it !in '!'..'~' }) {
                throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
            }
            val raw = clients.getValue(provider).generate(key, model, contract.request(topic, native))
            val lesson = contract.read(raw)
            runCatching { cache.put(topic, native, lesson) }
            lesson
        } catch (e: CheckFailure) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
        }
    }
}
