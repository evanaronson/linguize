package com.evanaronson.linguize

import com.evanaronson.linguize.core.memory.FactCategory
import com.evanaronson.linguize.core.memory.MemoryMessage
import com.evanaronson.linguize.core.memory.MemoryState
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.memory.MemoryStore
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.memory.MemoryPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LabUserMemoryTest {
    @get:Rule val folder = TemporaryFolder()

    private class MemoryClient(var answer: suspend () -> String) : ProviderClient {
        val requests = mutableListOf<Triple<String, String, GenerationRequest>>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += Triple(key, model, request)
            return answer()
        }
        override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer = error("not a check")
        override suspend fun models(key: String) = emptyList<String>()
    }

    private val prompt = MemoryPrompt.load { File("src/main/assets/$it").readText() }
    private val exchange = listOf(
        MemoryMessage(false, "Hola! D'on ets?"),
        MemoryMessage(true, "Visc a Gràcia i em preparo per una entrevista"),
    )
    private val learned = """{"add":[
        {"text":"Lives in Gràcia, Barcelona","category":"about"},
        {"text":"Preparing for a job interview in Catalan","category":"goals"}],"update":[],"remove":[]}"""

    private fun memory(
        client: MemoryClient,
        store: MemoryStore = MemoryStore(File(folder.root, MemoryStore.FILE_NAME)),
        keys: Map<Provider, String> = mapOf(Provider.Gemini to "g-key"),
        prefs: FakePreferences = FakePreferences(),
    ) = LabUserMemory(prefs, ProviderKeys { keys[it] }, Provider.entries.associateWith { client }, store) { prompt }

    @Test fun learnsFromAnExchangeWithTheChosenModelAndShowsItInThePrompt() = runBlocking {
        val client = MemoryClient { learned }
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        val prefs = FakePreferences().apply { nativeLanguage = "Catalan"; models[Provider.Gemini] = "chosen" }
        val memory = memory(client, store, prefs = prefs)
        assertEquals("", memory.forPrompt())

        memory.learnFrom("chat:1", exchange)

        val (key, model, request) = client.requests.single()
        assertEquals("g-key", key)
        assertEquals("chosen", model)
        assertTrue(request.user.contains("\"writeIn\":\"Catalan\""))
        assertEquals(listOf(listOf("chat:1"), listOf("chat:1")), store.load().facts.map { it.sources })
        val block = memory.forPrompt()
        assertTrue(block.startsWith(MemoryState.PROMPT_HEADER))
        assertTrue(block.contains("- Lives in Gràcia, Barcelona"))
    }

    @Test fun updatesMergeSourcesAcrossChats() = runBlocking {
        val client = MemoryClient { learned }
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        val memory = memory(client, store)
        memory.learnFrom("chat:1", exchange)
        client.answer = { """{"add":[],"update":[{"id":"f1","text":"Lives in Sants, Barcelona","category":"about"}],"remove":[]}""" }
        memory.learnFrom("chat:2", exchange)
        val fact = store.load().facts.first()
        assertEquals("Lives in Sants, Barcelona", fact.text)
        assertEquals(listOf("chat:1", "chat:2"), fact.sources)

        memory.forget("chat:1")
        assertEquals(listOf("Lives in Sants, Barcelona"), store.load().facts.map { it.text })
        memory.forget("chat:2")
        assertTrue(store.load().facts.isEmpty())
    }

    @Test fun offLearnsNothingAndPromptsNothingButKeepsFacts() = runBlocking {
        val client = MemoryClient { learned }
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        store.add("Prefers casual Spanish", FactCategory.Preferences)
        store.setEnabled(false)
        val memory = memory(client, store)
        memory.learnFrom("chat:1", exchange)
        assertTrue(client.requests.isEmpty())
        assertEquals("", memory.forPrompt())
        assertEquals(1, store.load().facts.size)
    }

    @Test fun switchedOffWhileTheModelAnswersLearnsNothing() = runBlocking {
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        val client = MemoryClient { store.setEnabled(false); learned }
        memory(client, store).learnFrom("chat:1", exchange)
        assertTrue(store.load().facts.isEmpty())
    }

    @Test fun deletedWhileTheModelAnswersLearnsNothing() = runBlocking {
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        lateinit var memory: LabUserMemory
        val client = MemoryClient { memory.forget("chat:1"); learned }
        memory = memory(client, store)
        memory.learnFrom("chat:1", exchange)
        assertTrue(store.load().facts.isEmpty())
    }

    @Test fun noKeyOrAFailureSilentlyLearnsNothing() = runBlocking {
        val store = MemoryStore(File(folder.root, MemoryStore.FILE_NAME))
        val client = MemoryClient { learned }
        memory(client, store, keys = emptyMap()).learnFrom("chat:1", exchange)
        assertTrue(client.requests.isEmpty())

        client.answer = { throw CheckFailure(CheckFailure.Reason.Offline) }
        memory(client, store).learnFrom("chat:1", exchange)
        client.answer = { "not json" }
        memory(client, store).learnFrom("chat:1", exchange)
        assertTrue(store.load().facts.isEmpty())
    }

    @Test fun anExchangeWithNothingFromTheWriterIsNotSent() = runBlocking {
        val client = MemoryClient { learned }
        memory(client).learnFrom("chat:1", listOf(MemoryMessage(false, "Hola!"), MemoryMessage(true, "  ")))
        assertTrue(client.requests.isEmpty())
    }
}
