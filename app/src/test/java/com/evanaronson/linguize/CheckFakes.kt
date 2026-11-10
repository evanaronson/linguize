package com.evanaronson.linguize

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.generation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.Locale

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
internal class FakeClient(@Volatile var answer: suspend (RecordedCheck) -> ModelAnswer) : ProviderClient {
    /** The key, model and request of each check, in order. */
    val checks: MutableList<Triple<String, String, RecordedCheck>> = Collections.synchronizedList(mutableListOf())

    override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
        val recorded = RecordedCheck(request)
        checks += Triple(key, model, recorded)
        // The fixture's raw bytes are the provider boundary. Never synthesize a successful
        // answer from its verdict: malformed wire fixtures must reach the real parser.
        return answer(recorded).raw
    }

    override suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer {
        val recorded = RecordedCheck(Prompt("", "{}").generation(request))
        checks += Triple(key, model, recorded)
        return answer(recorded)
    }

    override suspend fun models(key: String): List<String> = listOf("m-2", "m-1")
}

/** Read-only observations of the actual wire request for card and repair behavior tests. */
internal class RecordedCheck(val generation: GenerationRequest) {
    private val header = generation.user.substringBefore("\nText: <text>").split('\n')
    private fun setting(name: String): String = header.first { it.startsWith("$name: ") }.substringAfter(": ")
    val text: String get() = generation.user.substringAfter("\nText: <text>").removeSuffix("</text>")
    val language: Language? get() {
        val name = setting("Language").substringBefore(" (")
        return if (name == "auto") null else Locale.getISOLanguages().asSequence()
            .mapNotNull(Language::forCode).first { it.name == name }
    }
    val punctuation: Punctuation get() = Punctuation.tokens.decode(setting("Punctuation"))!!
    val judgments: Judgments get() = Judgments.tokens.decode(setting("Checks"))!!
    val native: String get() = setting("Native").substringBefore(" (write meaning, assumptions and reasons in ")
    val repair: Boolean get() = "Repair: true" in header
    val writingProfiles: List<WritingProfile> get() = header.firstOrNull { it.startsWith("Writing profiles: ") }
        ?.substringAfter(": ")?.let { Json.decodeFromString<List<WritingProfile>>(it) }.orEmpty()
    val settled: List<Settled> get() = header.filter { it.startsWith("Settled: ") }.map {
        val fields = Json.parseToJsonElement(it.substringAfter(": ")).jsonObject
        val answer = fields.getValue("answer").jsonPrimitive.content
        if (fields["scope"]?.jsonPrimitive?.content == "meaning") Settled.meaning(answer)
        else Settled(fields.getValue("about").jsonPrimitive.content, answer)
    }
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
