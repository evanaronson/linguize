package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.GenerationRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/** One group as the model sees it: no session ids, dates or app names. */
data class PatternBrief(
    val id: String,
    val name: String,
    val times: Int,
    val checks: Int,
    /** fewer, steady, more or unknown. */
    val trend: String,
    val examples: List<ExampleBrief>,
)

data class ExampleBrief(val before: String, val after: String, val reason: String?)

@Serializable
data class PatternTip(val pattern: String, val tip: String)

@Serializable
data class PatternsReply(val summary: String, val tips: List<PatternTip>)

/**
 * The optional "Explain my patterns" request: its own prompt asset and schema, separate
 * from the check prompt. It sends group names, counts and a few examples, never whole
 * messages, and reads back a summary and per-group tips.
 */
class PatternsPrompt(private val system: String) {
    /** Identifies this prompt and schema in saved explanations. */
    val version: String = "lab-patterns-" + MessageDigest.getInstance("SHA-256")
        .digest((system + SCHEMA).toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    fun request(patterns: List<PatternBrief>, native: String): GenerationRequest {
        val user = buildJsonObject {
            put("explainIn", native)
            putJsonArray("groups") {
                patterns.take(MAX_GROUPS).forEach { pattern ->
                    addJsonObject {
                        put("id", pattern.id)
                        put("name", pattern.name)
                        put("times", pattern.times)
                        put("checks", pattern.checks)
                        put("trend", pattern.trend)
                        putJsonArray("examples") {
                            pattern.examples.take(MAX_EXAMPLES).forEach { example ->
                                addJsonObject {
                                    put("before", example.before.take(MAX_FIELD))
                                    put("after", example.after.take(MAX_FIELD))
                                    example.reason?.let { put("reason", it.take(MAX_FIELD)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        return GenerationRequest(system, user.toString(), json.parseToJsonElement(SCHEMA).jsonObject, "patterns", 1536)
    }

    /** Reads and checks the reply; tips must name groups that were sent, once each. */
    fun read(raw: String, sent: Set<String>): PatternsReply = try {
        if (raw.length > 32_768) throw invalid()
        val reply = json.decodeFromString<PatternsReply>(raw)
        val summary = reply.summary.trim()
        if (summary.isEmpty() || summary.length > 2_000 || reply.tips.size > MAX_GROUPS) throw invalid()
        val tips = reply.tips.map { it.copy(pattern = it.pattern.trim(), tip = it.tip.trim()) }
        if (tips.any { it.pattern !in sent || it.tip.isEmpty() || it.tip.length > 1_000 } ||
            tips.map { it.pattern }.toSet().size != tips.size
        ) throw invalid()
        PatternsReply(summary, tips)
    } catch (e: SerializationException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    } catch (e: IllegalArgumentException) {
        throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e, userMessage = CheckFailure.Message.InvalidReply)
    }

    private fun invalid() = CheckFailure(CheckFailure.Reason.BadResponse, userMessage = CheckFailure.Message.InvalidReply)

    companion object {
        const val ASSET = "lab_patterns_prompt.md"
        const val MAX_GROUPS = 8
        const val MAX_EXAMPLES = 4
        const val MAX_FIELD = 300

        fun load(read: (String) -> String) = PatternsPrompt(read(ASSET))

        private val json = Json { ignoreUnknownKeys = false }

        private val SCHEMA = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("summary", buildJsonObject { put("type", "string") })
                put("tips", buildJsonObject {
                    put("type", "array")
                    put("maxItems", MAX_GROUPS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("pattern", buildJsonObject { put("type", "string") })
                            put("tip", buildJsonObject { put("type", "string") })
                        })
                        putJsonArray("required") { add("pattern"); add("tip") }
                        put("additionalProperties", false)
                    })
                })
            })
            putJsonArray("required") { add("summary"); add("tips") }
            put("additionalProperties", false)
        }.toString()
    }
}
