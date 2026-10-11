package com.evanaronson.linguize.llm

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import java.util.concurrent.ConcurrentHashMap

/** What a check asks of the model; [Prompt] turns it into the user message. */
data class CheckRequest(
    val text: String,
    /** Null to let the model detect the language. */
    val language: Language?,
    val punctuation: Punctuation,
    val judgments: Judgments,
    /** The writer's answers to earlier assumptions. */
    val settled: List<Settled> = emptyList(),
    /** The writer's own language, for everything they read: the meaning, assumptions and reasons. */
    val native: String = "English",
)

/** The model's answer: [raw], the text exactly as it came back, and the [verdict] read from it. */
data class ModelAnswer(val verdict: Verdict, val raw: String)

/** One provider's API. Every client sends the same [Prompt] and returns the same [ModelAnswer]. */
interface ProviderClient {
    /** Throws [CheckFailure]; one about an answer that couldn't be read carries it as [CheckFailure.raw]. */
    suspend fun check(key: String, model: String, request: CheckRequest): ModelAnswer

    /** Models this key can use for checks, newest first. Throws [CheckFailure]. */
    suspend fun models(key: String): List<String>
}

/** [raw] read as a verdict, kept alongside it; a failure to read it keeps it too. */
internal fun Prompt.read(raw: String): ModelAnswer = try {
    ModelAnswer(parseVerdict(raw), raw)
} catch (e: CheckFailure) {
    throw CheckFailure(e.reason, e.detail, e.cause ?: e, raw = raw)
}

/**
 * A request option that keeps checks fast but that some models refuse (Gemini's minimal
 * thinking, OpenAI's reasoning effort "none"). [send] sends it with the option, unless the
 * model is known to refuse it, and again without it when the answer is a 400 that
 * mentions [refusal]; a model that refused it is remembered, so its next checks go without
 * it straight away. One per client, for the life of the process.
 */
internal class DroppableOption(private val refusal: String) {
    private val refusing = ConcurrentHashMap.newKeySet<String>()

    /** Sends with [request] (told whether to include the option); returns the last status code and payload. */
    suspend fun send(model: String, request: suspend (withOption: Boolean) -> Pair<Int, String>): Pair<Int, String> {
        if (model !in refusing) {
            val (code, payload) = request(true)
            if (code != 400 || refusal !in payload.lowercase()) return code to payload
            refusing += model
        }
        return request(false)
    }
}

/** Drops models built for other jobs (speech, images, embeddings…) and sorts the rest newest first. */
internal fun textModels(ids: List<String>): List<String> = ids
    .filterNot { id -> SPECIAL_PURPOSE.any { it in id } }
    .distinct()
    .sortedWith(compareByDescending<String> { version(it) }.thenBy { it })

/** The first number in a model name, e.g. 3.5 in "gemini-3.5-flash-lite". */
private fun version(id: String): Double = Regex("""\d+(\.\d+)?""").find(id)?.value?.toDoubleOrNull() ?: 0.0

private val SPECIAL_PURPOSE = listOf(
    "embedding", "tts", "image", "live", "audio", "realtime", "transcribe",
    "search", "robotics", "computer-use", "instruct", "moderation", "codex",
)
