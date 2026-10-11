package com.evanaronson.linguize

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import java.util.Collections

/** Settings as a test sets them. */
internal class FakePreferences : CheckPreferences {
    @Volatile override var provider = Provider.Gemini
    @Volatile override var punctuation = Punctuation.Moderate
    @Volatile override var judgments = Judgments.Both
    @Volatile override var nativeLanguage = "English"
    val models: MutableMap<Provider, String> = Collections.synchronizedMap(mutableMapOf())

    override fun model(provider: Provider): String? = models[provider]
}

/** A provider that answers with [answer] (or throws what it throws) and remembers every request. */
internal class FakeClient(@Volatile var answer: suspend (CheckRequest) -> ModelAnswer) : ProviderClient {
    /** The key, model and request of each check, in order. */
    val checks: MutableList<Triple<String, String, CheckRequest>> = Collections.synchronizedList(mutableListOf())

    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
        checks += Triple(key, model, request)
        return answer(request)
    }

    override suspend fun models(key: String): List<String> = listOf("m-2", "m-1")
}

/** A checker on fakes: [keys] are the saved keys, every provider answers through [client]. */
internal fun fakeChecker(
    preferences: FakePreferences,
    client: FakeClient,
    keys: MutableMap<Provider, String> = mutableMapOf(Provider.Gemini to "g-key", Provider.OpenAI to "o-key"),
    prompt: Prompt = Prompt(system = "instructions", schemaJson = "{}"),
) = Checker(preferences, { keys[it] }, prompt, Provider.entries.associateWith { client })

/** "Com estas?" with one fix, as the model would answer it. */
internal const val QUESTION = "Com estas?"

internal val QUESTION_VERDICT = Verdict(status = Verdict.Status.Ok, hasErrors = true, corrected = "Com estàs?")

/** [QUESTION_VERDICT] as the model's JSON. */
internal const val QUESTION_JSON = """{"status":"ok","has_errors":true,"corrected":"Com estàs?"}"""
