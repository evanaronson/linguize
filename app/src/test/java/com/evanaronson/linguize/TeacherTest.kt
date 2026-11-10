package com.evanaronson.linguize

import com.evanaronson.linguize.core.teachme.Lesson
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.data.teachme.LessonCache
import com.evanaronson.linguize.data.teachme.LessonKey
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.teachme.LESSON_JSON
import com.evanaronson.linguize.llm.teachme.LessonPrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TeacherTest {
    private class MemoryCache : LessonCache {
        val lessons = mutableMapOf<LessonKey, Lesson>()
        override fun get(topic: LessonTopic, native: String) = lessons[LessonKey.of(topic, native)]
        override fun put(topic: LessonTopic, native: String, lesson: Lesson) { lessons[LessonKey.of(topic, native)] = lesson }
    }

    private class LessonClient(var answer: () -> String = { LESSON_JSON }) : ProviderClient {
        val requests = mutableListOf<Triple<String, String, GenerationRequest>>()
        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            requests += Triple(key, model, request)
            return answer()
        }
        override suspend fun models(key: String) = emptyList<String>()
    }

    private val topic = LessonTopic("vaig poder", "podré", "Future, not past", "Catalan")
    private val prompt = LessonPrompt.load { File("src/main/assets/$it").readText() }

    private fun teacher(
        client: LessonClient,
        cache: LessonCache = MemoryCache(),
        prefs: FakePreferences = FakePreferences(),
        keys: Map<Provider, String> = mapOf(Provider.Gemini to "g-key"),
    ) = Teacher(prefs, ProviderKeys { keys[it] }, Provider.entries.associateWith { client }, cache) { prompt }

    @Test fun asksTheChosenModelInTheWritersLanguageAndSavesTheLesson() = runBlocking {
        val client = LessonClient()
        val cache = MemoryCache()
        val prefs = FakePreferences().apply { nativeLanguage = "Catalan"; models[Provider.Gemini] = "chosen" }
        val lesson = teacher(client, cache, prefs).teach(topic)
        assertEquals("Catalan", lesson.language)
        val (key, model, request) = client.requests.single()
        assertEquals("g-key", key)
        assertEquals("chosen", model)
        assertEquals("lesson", request.name)
        assertTrue(request.user.contains("\"explainIn\":\"Catalan\""))
        assertEquals(lesson, cache.get(topic, "Catalan"))
    }

    @Test fun aRepeatMistakeOpensFromTheCacheWithoutTheNetwork() = runBlocking {
        val client = LessonClient()
        val cache = MemoryCache()
        val first = teacher(client, cache).teach(topic)
        client.answer = { throw CheckFailure(CheckFailure.Reason.Offline) }
        val offline = teacher(client, cache, keys = emptyMap())
        assertEquals(first, offline.teach(topic))
        assertEquals(first, offline.saved(topic))
        assertEquals(1, client.requests.size)
    }

    @Test fun noKeyIsAFailureAndNothingIsSent() = runBlocking {
        val client = LessonClient()
        val failure = runCatching { teacher(client, keys = emptyMap()).teach(topic) }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.NoKey, failure.reason)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun aKeyWithSpacesIsRejectedBeforeSending() = runBlocking {
        val client = LessonClient()
        val failure = runCatching { teacher(client, keys = mapOf(Provider.Gemini to "bad key")).teach(topic) }
            .exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.BadKey, failure.reason)
        assertTrue(client.requests.isEmpty())
    }

    @Test fun anUnusableAnswerIsNotSaved() = runBlocking {
        val cache = MemoryCache()
        val client = LessonClient { "{\"rule\":\"only\"}" }
        val failure = runCatching { teacher(client, cache).teach(topic) }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.BadResponse, failure.reason)
        assertTrue(cache.lessons.isEmpty())
    }

    @Test fun transportFailuresPassThrough() = runBlocking {
        val client = LessonClient { throw CheckFailure(CheckFailure.Reason.RateLimited) }
        val failure = runCatching { teacher(client).teach(topic) }.exceptionOrNull() as CheckFailure
        assertEquals(CheckFailure.Reason.RateLimited, failure.reason)
    }

    @Test fun aBrokenCacheDoesntBlockALesson() = runBlocking {
        val broken = object : LessonCache {
            override fun get(topic: LessonTopic, native: String): Lesson = throw IllegalStateException("disk")
            override fun put(topic: LessonTopic, native: String, lesson: Lesson) = throw IllegalStateException("disk")
        }
        val teacher = teacher(LessonClient(), broken)
        assertNull(teacher.saved(topic))
        assertEquals("Catalan", teacher.teach(topic).language)
    }

    @Test fun anUnteachableTopicIsNeverSent() = runBlocking {
        val client = LessonClient()
        val failure = runCatching { teacher(client).teach(LessonTopic("a", "a", "same")) }.exceptionOrNull()
        assertTrue(failure is CheckFailure)
        assertTrue(client.requests.isEmpty())
    }
}
