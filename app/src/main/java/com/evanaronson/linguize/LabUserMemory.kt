package com.evanaronson.linguize

import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.UserMemory
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.memory.MemoryPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lab: "What Linguize knows" (docs/lab/memory.md). Facts live in [store]; learning asks the
 * provider and model chosen in settings with [prompt]'s own contract. With memory switched
 * off, [forPrompt] is "" and [learnFrom] does nothing; facts stay until the writer deletes
 * them. Without a key, or when the model fails or answers badly, nothing is learned and
 * nothing is thrown.
 */
class LabUserMemory(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, ProviderClient>,
    private val store: MemoryStore,
    prompt: () -> MemoryPrompt,
) : UserMemory {
    private val contract by lazy(prompt)

    /** One exchange learned at a time, so two never propose against the same facts. */
    private val learning = Mutex()

    override suspend fun forPrompt(): String = withContext(Dispatchers.IO) {
        try {
            store.load().promptBlock()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun learnFrom(source: String, messages: List<MemoryMessage>): Boolean {
        if (source.isBlank() || messages.none { it.fromWriter && it.text.isNotBlank() }) return false
        val generation = store.learningGeneration
        return learning.withLock {
            withContext(Dispatchers.IO) {
                try {
                    learn(source, messages, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Learning is best effort: a failed exchange teaches nothing.
                    false
                }
            }
        }
    }

    private suspend fun learn(source: String, messages: List<MemoryMessage>, generation: Long): Boolean {
        val before = store.learningSnapshot(source, generation) ?: return false
        val provider = settings.provider
        val key = keys.get(provider)?.takeUnless { it.isBlank() || it.any { c -> c !in '!'..'~' } } ?: return false
        val model = settings.model(provider) ?: provider.recommendedModel
        val facts = before.facts
        if (!store.isCurrentLearning(source, generation)) return false
        val raw = clients.getValue(provider).generate(key, model, contract.request(facts, messages, settings.nativeLanguage))
        val changes = contract.read(raw, facts)
        var committed = false
        store.update { state, now, newId ->
            if (!state.enabled || !store.isCurrentLearning(source, generation)) state else {
                committed = true
                state.applying(changes, source, now, newId)
            }
        }
        return committed
    }

    override suspend fun forget(source: String) {
        store.invalidateSource(source)
        withContext(Dispatchers.IO) {
            store.forgetSource(source)
        }
    }
}
