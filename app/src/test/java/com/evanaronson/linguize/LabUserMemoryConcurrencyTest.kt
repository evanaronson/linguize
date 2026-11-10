package com.evanaronson.linguize

import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryChanges
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.NewFact
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.memory.MemoryPrompt
import com.evanaronson.linguize.testing.KnownBonanzaDefect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real learning coordinator, prompt parser and disk store; only the provider is controlled. */
class LabUserMemoryConcurrencyTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, MemoryStore.FILE_NAME)
    private val prompt = MemoryPrompt.load { File("src/main/assets/$it").readText() }
    private val paris = """{"add":[{"text":"Lives in Paris","category":"about"}],"update":[],"remove":[]}"""
    private val london = """{"add":[],"update":[{"id":"f1","text":"Lives in London","category":"about"}],"remove":[]}"""

    private class Client(private val answer: suspend (GenerationRequest) -> String) : ProviderClient {
        val requests = mutableListOf<GenerationRequest>()

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += request
            return answer(request)
        }

        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("not a check")
        override suspend fun models(key: String) = emptyList<String>()
    }

    private class ReplyGate {
        val started = CompletableDeferred<GenerationRequest>()
        val answer = CompletableDeferred<String>()

        suspend fun respond(request: GenerationRequest): String {
            started.complete(request)
            return answer.await()
        }
    }

    private fun memory(client: Client, store: MemoryStore) = LabUserMemory(
        FakePreferences(), ProviderKeys { "test-key" }, Provider.entries.associateWith { client }, store,
    ) { prompt }

    private fun exchange(text: String) = listOf(MemoryMessage(true, text))

    // Gates determine ordering; this timeout only turns a broken handshake into a bounded failure.
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000, block)
    }

    @Test fun writerEditDuringGenerationSurvivesTheStaleProposalAndRestart() = scenario {
        val store = MemoryStore(file)
        store.update { state, now, newId ->
            state.applying(MemoryChanges(add = listOf(NewFact("Lives in Paris", FactCategory.About))), "chat:old", now, newId)
        }
        val factId = store.load().facts.single().id
        val gate = ReplyGate()
        val memory = memory(Client(gate::respond), store)

        val learning = launch { memory.learnFrom("chat:new", exchange("I live in London now")) }
        val request = gate.started.await()
        store.edit(factId, "Lives in Barcelona", FactCategory.About)
        gate.answer.complete(london)
        learning.join()

        // The provider really saw the old text; the commit must consult the newer writer ownership.
        assertEquals("Lives in Paris", Json.parseToJsonElement(request.user).jsonObject["facts"]!!
            .jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val saved = MemoryStore(file).load().facts.single()
        assertEquals("Lives in Barcelona", saved.text)
        assertTrue(saved.byWriter)
        assertEquals(listOf("chat:old"), saved.sources)
        assertEquals(saved, store.load().facts.single())
    }

    @Test fun queuedLearningBuildsItsRequestFromThePreviousCommittedAnswer() = scenario {
        val store = MemoryStore(file)
        val gate = ReplyGate()
        var calls = 0
        val client = Client { request -> if (calls++ == 0) gate.respond(request) else london }
        val memory = memory(client, store)
        val first = launch { memory.learnFrom("chat:first", exchange("I live in Paris")) }
        gate.started.await()

        // UNDISPATCHED starts the second call up to its suspended mutex acquisition.
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            memory.learnFrom("chat:second", exchange("I moved to London"))
        }
        gate.answer.complete(paris)
        first.join()
        second.join()

        assertEquals(2, client.requests.size)
        val secondFacts = Json.parseToJsonElement(client.requests[1].user).jsonObject["facts"]!!.jsonArray
        assertEquals("Lives in Paris", secondFacts.single().jsonObject["text"]!!.jsonPrimitive.content)
        val saved = MemoryStore(file).load().facts.single()
        assertEquals("Lives in London", saved.text)
        assertTrue("chat:second" in saved.sources)
    }

    @Test fun cancellingGenerationKeepsSavedFactsAndReleasesTheLearningQueue() = scenario {
        val store = MemoryStore(file)
        store.add("Prefers casual Spanish", FactCategory.Preferences)
        val before = store.load()
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val client = Client {
            if (calls++ == 0) {
                started.complete(Unit)
                awaitCancellation()
            }
            paris
        }
        val memory = memory(client, store)
        val cancelled = launch { memory.learnFrom("chat:cancelled", exchange("I live in Rome")) }
        started.await()
        cancelled.cancelAndJoin()

        assertTrue(cancelled.isCancelled)
        assertEquals(before, store.load())
        assertEquals(before, MemoryStore(file).load())
        memory.learnFrom("chat:next", exchange("I live in Paris"))
        assertEquals(listOf("Prefers casual Spanish", "Lives in Paris"), MemoryStore(file).load().facts.map { it.text })
        assertFalse("chat:cancelled" in store.load().sources)
    }

    @Category(KnownBonanzaDefect::class)
    @Test fun clearingMemoryInvalidatesAnAlreadyRunningLearningRequest() = scenario {
        val store = MemoryStore(file)
        store.add("Prefers casual Spanish", FactCategory.Preferences)
        val gate = ReplyGate()
        val memory = memory(Client(gate::respond), store)
        val learning = launch { memory.learnFrom("chat:old", exchange("I live in Paris")) }
        gate.started.await()

        store.clear()
        gate.answer.complete(paris)
        learning.join()

        assertTrue("Forget everything must invalidate answers requested before it", store.load().facts.isEmpty())
        assertTrue(MemoryStore(file).load().facts.isEmpty())
        assertTrue(store.load().enabled)
    }

    @Category(KnownBonanzaDefect::class)
    @Test fun forgettingAQueuedSourcePreventsItsMessagesFromReachingTheProvider() = scenario {
        val store = MemoryStore(file)
        val gate = ReplyGate()
        var calls = 0
        val client = Client { request -> if (calls++ == 0) gate.respond(request) else london }
        val memory = memory(client, store)
        val first = launch { memory.learnFrom("chat:first", exchange("I live in Paris")) }
        gate.started.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            memory.learnFrom("chat:deleted", exchange("I moved to London"))
        }

        memory.forget("chat:deleted")
        gate.answer.complete(paris)
        first.join()
        queued.join()

        assertEquals("A queued request must respect a source forgotten while it waited", 1, client.requests.size)
        assertEquals(listOf("chat:first"), MemoryStore(file).load().facts.single().sources)
        assertEquals("Lives in Paris", store.load().facts.single().text)
    }

    @Category(KnownBonanzaDefect::class)
    @Test fun forgettingTheSourceOfReplacementTextDoesNotAttributeItToTheOldSource() = scenario {
        val store = MemoryStore(file)
        var calls = 0
        val memory = memory(Client { if (calls++ == 0) paris else london }, store)
        memory.learnFrom("chat:paris", exchange("I live in Paris"))
        memory.learnFrom("chat:london", exchange("I moved to London"))

        memory.forget("chat:london")

        // Restoring Paris or conservatively dropping the fact are both valid future fixes.
        // Keeping London while citing only the Paris conversation is not.
        assertFalse("The removed source was the only evidence for London",
            MemoryStore(file).load().facts.any { it.text == "Lives in London" })
        assertFalse("chat:london" in store.load().sources)
    }
}
