package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.core.WritingProfile
import com.evanaronson.linguize.core.Links
import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.eval.CheckPreparationExport
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.GenerationRequest
import com.evanaronson.linguize.llm.Prompt
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.ProviderClient
import com.evanaronson.linguize.llm.PreparedCheck
import com.evanaronson.linguize.llm.lab.CheckComposition
import com.evanaronson.linguize.llm.lab.GapsInCheck
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.llm.lab.ToneInCheck
import com.evanaronson.linguize.llm.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Collections

/** Fresh captures use Checker.check; explicitly historical captures use Checker.reuse and the same repair selection. */
class FuzzReplayTest {
    @Test fun replayEverySavedProviderAnswer() = runBlocking<Unit> {
        val path = System.getenv("LINGUIZE_FUZZ_REPLIES")
        assumeTrue("Live fuzz replay is opt-in and never makes provider requests", path != null)
        val replies = File(path!!)
        val output = File(System.getenv("LINGUIZE_FUZZ_ENGINE") ?: "build/fuzz/engine.jsonl")
        output.parentFile?.mkdirs()
        val prompt = Prompt.load { File("src/main/assets", it).readText() }
        val appGaps by lazy { GapsInCheck.load(::asset) }
        val appTone by lazy { ToneInCheck.load(::asset) }
        val reply by lazy { ReplyPrompt.load(::asset) }
        val parser = ReplayParser.from(System.getenv("LINGUIZE_FUZZ_PARSER"))
        println("Fuzz replay parser: ${parser.token}")
        var processed = 0
        val evidence = mutableListOf<JsonObject>()
        val safetyFailures = mutableListOf<String>()
        output.bufferedWriter().use { writer ->
            replies.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                processed++
                val entry = Json.parseToJsonElement(line).jsonObject
                evidence += entry
                val case = entry.getValue("case").jsonObject
                val original = case.getValue("text").jsonPrimitive.content
                val flags = mutableListOf<String>()
                val actions = mutableListOf<JsonObject>()
                val report = buildJsonObject {
                    put("request_id", case.getValue("request_id"))
                    put("replay_input", entry)
                    put("replay_parser", parser.token)
                    if ("error" in entry) {
                        put("result", "provider_error")
                        put("error", entry.getValue("error"))
                        return@buildJsonObject
                    }
                    try {
                        val repair = entry["repair"]?.jsonObject
                        val mode = replayMode(entry)
                        require(mode != "app" || parser == ReplayParser.Fresh) { "App evidence requires fresh replay" }
                        val client = ReplayClient(entry)
                        val preferences = FakePreferences().apply {
                            provider = Provider.entries.first { it.token == entry.getValue("provider").jsonPrimitive.content }
                            models[provider] = entry.getValue("model").jsonPrimitive.content
                        }
                        val gaps = if (mode == "app") appGaps else null
                        val tone = if (mode == "app") appTone else null
                        val replyingTo = CheckPreparationExport.replyingTo(case)
                        require(mode == "app" || replyingTo == null) { "Reply context requires app preparation mode" }
                        val checker = Checker(preferences, { "replay-key" }, prompt, Provider.entries.associateWith { client },
                            replyPrompt = { reply }, gapsInCheck = gaps?.let { { it } }, toneInCheck = tone?.let { { it } })
                        // Replay the request context actually recorded with this evidence. Older
                        // captures predate profiles; today's defaults must not be injected into them.
                        val recorded = recordedContext(runBlocking { checker.context(replyingTo) }, case)
                        val request = CheckPreparationExport.request(case).let {
                            if (mode == "base") it.copy(writingProfiles = recorded.writingProfiles) else it
                        }
                        val selected = request.language
                        val context = recorded.copy(punctuation = request.punctuation, judgments = request.judgments,
                            native = request.native, writingProfiles = request.writingProfiles)
                        val initial = CheckComposition.prepare(prompt, gaps, tone, request, reply, replyingTo)
                        val retry = CheckComposition.prepare(prompt, gaps, tone, request.copy(repair = true), reply, replyingTo)
                        if (mode == "app") verifyPreparation(entry, initial, retry)
                        put("replay_path", if (parser == ReplayParser.Fresh) "fresh_check" else "legacy_reuse")
                        val checked = runBlocking {
                            if (parser == ReplayParser.Fresh) checker.check(original, selected, request.settled, context, replyingTo)
                            else replayLegacy(checker, client, request, context, initial, retry)
                        }
                        val verdict = prompt.parseVerdict(checked.raw)
                        put("selected_verdict", Json.parseToJsonElement(checked.raw))
                        put("retry_evidence", if (client.generations.size == 1) "not_needed" else if (repair == null) "simulated" else "recorded")
                        if (repair != null && (repair["provider"] != entry["provider"] || repair["model"] != entry["model"]))
                            flags += "model_context_drift"
                        if (checked.context.provider != preferences.provider ||
                            checked.context.model != entry.getValue("model").jsonPrimitive.content)
                            flags += "model_context_drift"
                        val requests = client.generations
                        if (requests.first() != initial.generation || requests.size !in 1..2 ||
                            requests.drop(1).any { it != retry.generation })
                            flags += "request_contract_drift"
                        if (runBlocking { checker.reuse(original, selected, checked.raw, checked.context) }.result != checked.result)
                            flags += "reuse_differs_from_fresh_check"
                        when (val result = checked.result) {
                            CheckResult.Unclear -> put("result", "unclear")
                            is CheckResult.WrongLanguage -> {
                                put("result", "wrong_language")
                                put("expected", result.expected)
                                put("found", result.found?.let(::JsonPrimitive) ?: JsonNull)
                            }
                            is CheckResult.Reviewed -> {
                                put("result", "reviewed")
                                put("looks_good", result.looksGood)
                                put("withheld", buildJsonArray { result.withheld.forEach { add(it.token) } })
                                val revision = result.revision
                                val fixed = revision.acceptAll(EditKind.Fix)
                                val finished = fixed.acceptAll(EditKind.Natural).workingText
                                val boundaries = Sentences.ranges(original)
                                for (kind in EditKind.entries) {
                                    val edits = revision.edits(kind)
                                    for (edit in edits) {
                                        if (kind == EditKind.Natural &&
                                            edit.from.filter(Char::isDigit) != edit.replacement.filter(Char::isDigit))
                                            flags += "natural_changes_digits:" + edit.id
                                        if (kind == EditKind.Fix && bundledOrthography(edit.from, edit.replacement))
                                            flags += "bundled_word_fixes:" + edit.id
                                        if (edit.from.isEmpty() && edits.any { other ->
                                                other.id != edit.id && other.start == edit.start && other.from.isNotEmpty() &&
                                                    skeleton(edit.replacement + other.replacement) == skeleton(other.from)
                                            }) flags += "dependent_word_split:" + edit.id
                                        if (edit.start < 0 || edit.end > original.length ||
                                            original.substring(edit.start, edit.end) != edit.from)
                                            flags += "invalid_edit_span:" + edit.id
                                        if (kind == EditKind.Natural && Sentences.crosses(boundaries, edit.start, edit.end))
                                            flags += "natural_crosses_sentence:" + edit.id
                                        val single = revision.accept(edit.id)
                                        val expected = original.substring(0, edit.start) +
                                            edit.replacement + original.substring(edit.end)
                                        if (single.workingText != expected)
                                            flags += "single_accept_reconstruction:" + edit.id
                                        if (single.undo().workingText != original)
                                            flags += "single_undo:" + edit.id
                                        if (single.accept(edit.id) != single)
                                            flags += "single_accept_not_idempotent:" + edit.id
                                        if (Links.hasNewLink(original, single.workingText))
                                            flags += "single_accept_adds_link:" + edit.id
                                        if (single.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural).workingText != finished)
                                            flags += "single_accept_remaining_changes_text:" + edit.id
                                        val afterFixes = if (kind == EditKind.Natural) fixed.accept(edit.id) else null
                                        if (afterFixes != null && afterFixes.undo().workingText != fixed.workingText)
                                            flags += "natural_after_fixes_undo:" + edit.id
                                        actions += buildJsonObject {
                                            put("id", edit.id); put("kind", kind.token)
                                            put("start", edit.start); put("end", edit.end)
                                            put("from", edit.from); put("to", edit.replacement)
                                            put("why", edit.why?.let(::JsonPrimitive) ?: JsonNull)
                                            put("includes", buildJsonArray { edit.includes.forEach { add(it) } })
                                            put("accepted_text", single.workingText)
                                            afterFixes?.let { put("accepted_after_fixes_text", it.workingText) }
                                            put("remaining_fix_ids", buildJsonArray {
                                                single.remaining(EditKind.Fix).forEach { add(it.id) }
                                            })
                                        }
                                    }
                                    for (left in edits.indices) for (right in left + 1 until edits.size)
                                        if (edits[left].touches(edits[right])) flags += "same_kind_overlap:" + kind.token
                                    val all = revision.acceptAll(kind)
                                    if (all.workingText != revision.preview(kind).text)
                                        flags += "preview_accept_all_mismatch:" + kind.token
                                    if (all.undo().workingText != original)
                                        flags += "accept_all_undo:" + kind.token
                                }
                                val corrected = revision.acceptAll(EditKind.Fix).workingText
                                if (verdict.hasErrors && verdict.corrected.isNotBlank() &&
                                    !Links.hasNewLink(original, verdict.corrected) &&
                                    corrected != verdict.corrected.trim())
                                    flags += "fix_reconstruction_mismatch"
                                val fixFirst = revision.acceptAll(EditKind.Fix).acceptAll(EditKind.Natural)
                                val naturalFirst = revision.acceptAll(EditKind.Natural).acceptAll(EditKind.Fix)
                                if (fixFirst.workingText != naturalFirst.workingText)
                                    flags += "accept_order_changes_text"
                                if (Links.hasNewLink(original, fixFirst.workingText))
                                    flags += "accept_all_adds_link"
                                put("fix_preview", revision.preview(EditKind.Fix).text)
                                put("natural_preview", revision.preview(EditKind.Natural).text)
                                put("accepted_all", fixFirst.workingText)
                                put("meaning", result.meaning)
                                put("edits", JsonArray(actions))
                            }
                        }
                    } catch (error: Exception) {
                        put("result", "checker_error")
                        put("error", error.javaClass.simpleName)
                        put("detail", error.message ?: "")
                        flags += "checker_error"
                    }
                    put("flags", buildJsonArray { flags.distinct().forEach { add(it) } })
                    for (flag in flags.distinct()) if (flag.substringBefore(':') in safetyFlags)
                        safetyFailures += case.getValue("request_id").jsonPrimitive.content + ":" + flag
                }
                writer.appendLine(report.toString())
            }
        }
        assertEquals("Every named-language and Auto Detect request must be replayed", 200, processed)
        requireCompleteReplies(evidence)
        assertTrue("Replay violated deterministic safety gates: $safetyFailures", safetyFailures.isEmpty())
    }

    /** Fresh captures use the shipping reader; historical compatibility is always explicit. */
    private enum class ReplayParser(val token: String) {
        Fresh("fresh"), Legacy("legacy");

        fun read(prompt: Prompt, raw: String): ModelAnswer = when (this) {
            Fresh -> prompt.read(raw)
            Legacy -> ModelAnswer(prompt.parseVerdict(raw), raw)
        }

        companion object {
            fun from(value: String?): ReplayParser = entries.firstOrNull { it.token == (value ?: "fresh") }
                ?: error("LINGUIZE_FUZZ_PARSER must be fresh or legacy")
        }
    }

    /** Only raw saved evidence crosses this fake transport, exactly as a live provider returns it. */
    private class ReplayClient(private val entry: JsonObject) : ProviderClient {
        val generations: MutableList<GenerationRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun generate(key: String, model: String, request: GenerationRequest): String {
            generations += request
            val repair = entry["repair"]?.jsonObject
            return if (generations.size == 1 || repair == null) entry.getValue("verdict").toString()
            else {
                if ("error" in repair) throw CheckFailure(CheckFailure.Reason.BadResponse)
                repair.getValue("verdict").toString()
            }
        }

        override suspend fun models(key: String): List<String> = error("Replay never lists provider models")
    }

    private fun replayChecker(preferences: FakePreferences, client: ReplayClient, prompt: Prompt) =
        Checker(preferences, { "replay-key" }, prompt, Provider.entries.associateWith { client })

    /** Archived answers use the real replay parser; they are never disguised as fresh answers. */
    private suspend fun replayLegacy(
        checker: Checker,
        client: ReplayClient,
        request: CheckRequest,
        context: CheckContext,
        initial: PreparedCheck,
        retry: PreparedCheck,
    ): Checked {
        val first = checker.reuse(request.text, request.language,
            client.generate("replay-key", context.model, initial.generation), context)
        val partial = first.result as? CheckResult.Reviewed
        if (partial == null || partial.withheld.isEmpty()) return first
        val second = try {
            checker.reuse(request.text, request.language,
                client.generate("replay-key", context.model, retry.generation), context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return first
        }
        return selectRepairedCheck(first, second)
    }

    private fun verifyPreparation(entry: JsonObject, initial: PreparedCheck, retry: PreparedCheck) {
        require(entry["preparation_version"] == JsonPrimitive(1)) { "Unsupported recorded preparation version" }
        val first = CheckPreparationExport.preparedRequestRecord("app", initial)
        val repair = CheckPreparationExport.preparedRequestRecord("app", retry)
        val expected = mapOf(
            "contract_sha256" to first.getValue("contract_sha256"),
            "request_sha256" to first.getValue("request_sha256"),
            "repair_contract_sha256" to repair.getValue("contract_sha256"),
            "repair_request_sha256" to repair.getValue("request_sha256"),
            "parser" to first.getValue("parser"),
            "prompt_sha256" to first.getValue("contract_sha256"),
        )
        require(expected.all { (key, value) -> entry[key] == value }) { "Recorded preparation differs from the current check contract" }
    }

    private fun replayMode(entry: JsonObject): String {
        val mode = entry["mode"]?.jsonPrimitive?.content ?: "base"
        require(mode == "app" || mode == "base") { "Unknown recorded check mode" }
        require(mode == "app" || listOf("preparation_version", "contract_sha256", "request_sha256",
            "repair_contract_sha256", "repair_request_sha256", "parser").none { it in entry }) {
            "Base archive rows cannot contain app preparation metadata"
        }
        return mode
    }

    private fun asset(name: String): String = File("src/main/assets", name).readText()

    private fun scoredAnswer(kind: String, scores: String): JsonObject = Json.parseToJsonElement(
        """{"status":"ok","language":"Catalan","meaning":"","assumptions":[],
            "has_errors":${kind == "fixes"},"corrected":"","more_natural":${kind == "natural_changes"},"natural":"",
            "fixes":[],"natural_changes":[]}"""
            .replace("\"$kind\":[]", """"$kind":[{"from":"estas","to":"estàs","why":"Accent","assumption_indices":[]$scores}]""")
    ).jsonObject

    @Test fun freshReplayRejectsUnassessedAndQuotedScoresWithoutLosingRawEvidence() {
        val prompt = Prompt("", "{}")
        for (kind in listOf("fixes", "natural_changes")) for (scores in listOf(
            "", ""","confidence":null,"impact":null""",
            ""","confidence":"3","impact":1""", ""","confidence":1,"impact":"0"""",
        )) {
            val verdict = scoredAnswer(kind, scores)
            val client = ReplayClient(buildJsonObject { put("verdict", verdict) })
            val checker = replayChecker(FakePreferences(), client, prompt)
            val failure = assertThrows(CheckFailure::class.java) { runBlocking { checker.check(QUESTION, null) } }
            assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
            assertEquals(verdict.toString(), failure.raw)
            assertEquals(1, client.generations.size)
        }
        assertThrows(IllegalStateException::class.java) { ReplayParser.from("unknown") }
    }

    @Test fun freshReplayAcceptsEveryNumericScoreAndLegacyReplayRemainsExplicit() {
        val prompt = Prompt("", "{}")
        for (kind in listOf("fixes", "natural_changes")) for (score in 0..10) {
            val raw = scoredAnswer(kind, ""","confidence":$score,"impact":$score""").toString()
            val answer = ReplayParser.from(null).read(prompt, raw)
            assertEquals(raw, answer.raw)
            assertEquals(score, (answer.verdict.fixes + answer.verdict.naturalChanges).single().assessment!!.confidence)
        }
        val legacy = ReplayParser.from("legacy")
        assertEquals("legacy", legacy.token)
        val raw = scoredAnswer("fixes", "").toString()
        val answer = legacy.read(prompt, raw)
        assertEquals(raw, answer.raw)
        assertEquals(null, answer.verdict.fixes.single().assessment)
    }

    @Test fun invalidRecordedRepairKeepsTheValidPartialFirstAnswer() = runBlocking<Unit> {
        val prompt = Prompt("", "{}")
        val first = Json.parseToJsonElement(
            """{"status":"ok","has_errors":true,"corrected":"Com estàs?","more_natural":true}"""
        )
        val entry = buildJsonObject {
            put("verdict", first)
            put("repair", buildJsonObject { put("verdict", scoredAnswer("fixes", "")) })
        }
        val client = ReplayClient(entry)
        val checker = replayChecker(FakePreferences(), client, prompt)
        val checked = checker.check(QUESTION, null)
        assertEquals(first.toString(), checked.raw)
        assertTrue((checked.result as CheckResult.Reviewed).withheld.isNotEmpty())
        assertEquals(2, client.generations.size)
        assertTrue("\nRepair: true\nText: " in client.generations.last().user)
        assertEquals(checked.result, checker.reuse(QUESTION, null, checked.raw, checked.context).result)
    }

    @Test fun legacyUnscoredRepairUsesTheReplayParserAndOriginalEvidence() = runBlocking<Unit> {
        val prompt = Prompt.load(::asset)
        val repaired = Json.parseToJsonElement(LEGACY_CORRECTION)
        val first = JsonObject(repaired.jsonObject + ("more_natural" to JsonPrimitive(true)))
        val entry = buildJsonObject {
            put("verdict", first)
            put("repair", buildJsonObject { put("verdict", repaired) })
        }
        val client = ReplayClient(entry)
        val checker = replayChecker(FakePreferences(), client, prompt)
        val context = checker.context().copy(writingProfiles = emptyList())
        val request = CheckRequest(QUESTION, Language.Catalan, context.punctuation, context.judgments)
        val initial = CheckComposition.prepare(prompt, null, null, request)
        val retry = CheckComposition.prepare(prompt, null, null, request.copy(repair = true))

        val checked = replayLegacy(checker, client, request, context, initial, retry)

        assertEquals(LEGACY_CORRECTION, checked.raw)
        val reviewed = checked.result as CheckResult.Reviewed
        assertTrue(reviewed.withheld.isEmpty())
        assertEquals(listOf(initial.generation, retry.generation), client.generations)
        assertEquals("Com estàs?", reviewed.revision.acceptAll(EditKind.Fix).workingText)
        assertEquals(checked.result, checker.reuse(QUESTION, Language.Catalan, checked.raw, context).result)
        val failure = assertThrows(CheckFailure::class.java) { initial.read(checked.raw) }
        assertEquals(CheckFailure.Message.InvalidReply, failure.userMessage)
        assertEquals(checked.raw, failure.raw)
    }

    @Test fun malformedLegacyRepairKeepsTheFirstRawAnswerAndTwoRecordedAttempts() = runBlocking<Unit> {
        val prompt = Prompt.load(::asset)
        val first = JsonObject(Json.parseToJsonElement(LEGACY_CORRECTION).jsonObject + ("more_natural" to JsonPrimitive(true)))
        val entry = buildJsonObject {
            put("verdict", first)
            put("repair", buildJsonObject { put("verdict", "not an answer object") })
        }
        val client = ReplayClient(entry)
        val checker = replayChecker(FakePreferences(), client, prompt)
        val context = checker.context().copy(writingProfiles = emptyList())
        val request = CheckRequest(QUESTION, Language.Catalan, context.punctuation, context.judgments)
        val initial = CheckComposition.prepare(prompt, null, null, request)
        val retry = CheckComposition.prepare(prompt, null, null, request.copy(repair = true))

        val checked = replayLegacy(checker, client, request, context, initial, retry)

        assertEquals(first.toString(), checked.raw)
        assertTrue((checked.result as CheckResult.Reviewed).withheld.isNotEmpty())
        assertEquals(listOf(initial.generation, retry.generation), client.generations)
    }

    @Test fun appCaptureRequiresMatchingPreparationParserAndBothRequestIdentities() {
        val prompt = Prompt.load(::asset)
        val gaps = GapsInCheck.load(::asset)
        val tone = ToneInCheck.load(::asset)
        val reply = ReplyPrompt.load(::asset)
        val request = CheckRequest(QUESTION, Language.Catalan, Punctuation.Moderate, Judgments.Both)
        val initial = CheckComposition.prepare(prompt, gaps, tone, request, reply, "Com va?")
        val retry = CheckComposition.prepare(prompt, gaps, tone, request.copy(repair = true), reply, "Com va?")
        val firstRecord = CheckPreparationExport.preparedRequestRecord("app", initial)
        val retryRecord = CheckPreparationExport.preparedRequestRecord("app", retry)
        val recorded = buildJsonObject {
            put("preparation_version", 1)
            put("contract_sha256", firstRecord.getValue("contract_sha256"))
            put("request_sha256", firstRecord.getValue("request_sha256"))
            put("repair_contract_sha256", retryRecord.getValue("contract_sha256"))
            put("repair_request_sha256", retryRecord.getValue("request_sha256"))
            put("parser", firstRecord.getValue("parser"))
            put("prompt_sha256", firstRecord.getValue("contract_sha256"))
        }
        verifyPreparation(recorded, initial, retry)
        for (key in recorded.keys) {
            assertThrows("A mismatch in $key must reject replay", IllegalArgumentException::class.java) {
                verifyPreparation(JsonObject(recorded + (key to JsonPrimitive("wrong"))), initial, retry)
            }
        }
        val wrongReply = CheckComposition.prepare(prompt, gaps, tone, request, reply, "Different reply")
        assertThrows(IllegalArgumentException::class.java) { verifyPreparation(recorded, wrongReply, retry) }
    }

    @Test fun recordedAppPreparationCannotBeRelabelledAsAnOlderBaseCapture() {
        assertEquals("base", replayMode(buildJsonObject {}))
        assertEquals("base", replayMode(buildJsonObject { put("mode", "base") }))
        for (field in listOf("preparation_version", "contract_sha256", "request_sha256",
            "repair_contract_sha256", "repair_request_sha256", "parser")) {
            val recorded = buildJsonObject { put("mode", "app"); put(field, "recorded") }
            assertEquals("app", replayMode(recorded))
            assertThrows(IllegalArgumentException::class.java) { replayMode(JsonObject(recorded - "mode")) }
            assertThrows(IllegalArgumentException::class.java) {
                replayMode(JsonObject(recorded + ("mode" to JsonPrimitive("base"))))
            }
        }
    }

    private companion object {
        const val LEGACY_CORRECTION = """{"status":"ok","language":"Catalan","meaning":"How are you?","assumptions":[],"has_errors":true,"corrected":"Com estàs?","fixes":[{"from":"estas","to":"estàs","why":"Accent"}],"more_natural":false,"natural":"","natural_changes":[]}"""
    }

    /** Missing profile data is genuinely legacy evidence, never permission to invent it. */
    private fun recordedContext(current: CheckContext, case: JsonObject): CheckContext = current.copy(
        punctuation = Punctuation.tokens.decode(case["punctuation"]?.jsonPrimitive?.content ?: "moderate")
            ?: error("Unknown recorded punctuation"),
        judgments = Judgments.tokens.decode(case["checks"]?.jsonPrimitive?.content ?: "both")
            ?: error("Unknown recorded judgments"),
        native = case["native"]?.jsonPrimitive?.content ?: "English",
        writingProfiles = case["writing_profiles"]?.let { Json.decodeFromJsonElement<List<WritingProfile>>(it) }.orEmpty(),
    )

    @Test fun replayPreservesLegacyAndProfileAwareRequestContexts() {
        val defaults = Language.all.map { WritingProfile.defaultFor(it) }
        val current = CheckContext(Provider.Gemini, "model", Punctuation.Strict, Judgments.FixOnly,
            "French", "hash", defaults)
        val legacy = recordedContext(current, buildJsonObject {
            put("punctuation", "moderate"); put("checks", "both"); put("native", "English")
        })
        assertTrue(legacy.writingProfiles.isEmpty())
        assertEquals(Punctuation.Moderate, legacy.punctuation)
        assertEquals(Judgments.Both, legacy.judgments)
        assertEquals("English", legacy.native)
        val profile = defaults.first().copy(register = com.evanaronson.linguize.core.WritingRegister.Formal)
        val recorded = recordedContext(current, buildJsonObject {
            put("writing_profiles", Json.encodeToJsonElement(listOf(profile)))
            put("punctuation", "casual"); put("checks", "naturalize"); put("native", "Spanish")
        })
        assertEquals(listOf(profile), recorded.writingProfiles)
        assertEquals(Punctuation.Casual, recorded.punctuation)
        assertEquals(Judgments.NaturalizeOnly, recorded.judgments)
        assertEquals("Spanish", recorded.native)
    }

    /** Capture failed requests above, but never count them as a completed audit. */
    private fun requireCompleteReplies(entries: List<JsonObject>) {
        val expected = Language.all.flatMap { language ->
            (1..50).flatMap { index ->
                listOf(language.name, "auto").map { mode ->
                    "${language.name.take(2).lowercase()}-${index.toString().padStart(3, '0')}:$mode"
                }
            }
        }.toSet()
        val cases = entries.map { it.getValue("case").jsonObject }
        val ids = cases.map { it.getValue("request_id").jsonPrimitive.content }
        require(ids.size == expected.size && ids.toSet() == expected) { "Replay requires all 200 unique corpus requests" }
        require(entries.all { "error" !in it && it["verdict"] is JsonObject }) { "Provider failures cannot complete an audit" }
        val passages = cases.groupBy { it.getValue("id").jsonPrimitive.content }
        require(passages.size == 100 && passages.values.all { pair ->
            pair.size == 2 && pair.map { it.getValue("text") }.distinct().size == 1 &&
                pair.map { it.getValue("original") }.distinct().size == 1 && pair.all { case ->
                    val id = case.getValue("id").jsonPrimitive.content
                    val language = case.getValue("language").jsonPrimitive.content
                    val source = case.getValue("source_language").jsonPrimitive.content
                    case.getValue("request_id").jsonPrimitive.content == "$id:$language" &&
                        language in listOf(source, "auto") &&
                        id.startsWith(source.take(2).lowercase() + "-") &&
                        case.getValue("original_sentences").jsonArray.size in 2..3 &&
                        case.getValue("text") != case.getValue("original")
                }
        } && passages.values.map { it.first().getValue("original") }.distinct().size == 100 &&
            passages.values.map { it.first().getValue("text") }.distinct().size == 100) {
            "Replay requires 100 distinct corrupted passages with matching named-language and Auto Detect pairs"
        }
    }

    @Test fun incompleteEvidenceCannotPassTheReplayGate() {
        val entries = Language.all.flatMap { language ->
            (1..50).flatMap { index ->
                listOf(language.name, "auto").map { mode ->
                    val id = "${language.name.take(2).lowercase()}-${index.toString().padStart(3, '0')}"
                    buildJsonObject {
                        put("case", buildJsonObject {
                            put("id", id)
                            put("request_id", "$id:$mode")
                            put("source_language", language.name)
                            put("language", mode)
                            put("original", "$id first. Second.")
                            put("original_sentences", buildJsonArray { add("$id first."); add("Second.") })
                            put("text", "$id firs. Second.")
                        })
                        put("verdict", buildJsonObject { put("status", "ok") })
                    }
                }
            }
        }
        requireCompleteReplies(entries)
        val failed = entries.map { JsonObject(it - "verdict" + ("error" to JsonPrimitive("timeout"))) }
        val duplicate = entries.dropLast(1) + entries.first()
        val wrongId = entries.toMutableList().apply {
            val case = first().getValue("case").jsonObject
            this[0] = JsonObject(first() + ("case" to JsonObject(case + ("request_id" to JsonPrimitive("unexpected:auto")))))
        }
        for (invalid in listOf(failed, duplicate, wrongId, entries.dropLast(1))) {
            assertTrue("Incomplete evidence must fail", runCatching { requireCompleteReplies(invalid) }.isFailure)
        }
    }

    // These are executable contracts. Reconstruction/orthography heuristics remain
    // review diagnostics: they cannot establish linguistic or semantic correctness.
    private val safetyFlags = setOf(
        "checker_error", "invalid_edit_span", "single_accept_reconstruction", "single_undo",
        "single_accept_not_idempotent", "same_kind_overlap", "preview_accept_all_mismatch",
        "accept_all_undo", "accept_order_changes_text", "natural_crosses_sentence",
        "natural_changes_digits", "single_accept_adds_link", "accept_all_adds_link",
        "single_accept_remaining_changes_text", "natural_after_fixes_undo",
        "model_context_drift", "request_contract_drift", "reuse_differs_from_fresh_check",
    )

    /** Formatting an existing word/number must not become two dependent Accept actions. */
    private fun skeleton(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .filter(Char::isLetterOrDigit)

    /** Independent orthographic repairs to different words cannot share one fix. */
    private fun bundledOrthography(from: String, to: String): Boolean {
        val before = from.trim().split(Regex("\\s+"))
        val after = to.trim().split(Regex("\\s+"))
        return before.size == after.size && before.zip(after).count { (a, b) ->
            a != b && skeleton(a).isNotEmpty() && skeleton(a) == skeleton(b)
        } > 1
    }
}
