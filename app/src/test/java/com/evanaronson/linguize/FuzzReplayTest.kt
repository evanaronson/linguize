package com.evanaronson.linguize

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Links
import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.llm.ModelAnswer
import com.evanaronson.linguize.llm.Prompt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/** Replays saved live answers through the production Checker and edit/Accept engine. */
class FuzzReplayTest {
    @Test fun replayEverySavedProviderAnswer() = runBlocking<Unit> {
        val path = System.getenv("LINGUIZE_FUZZ_REPLIES")
        assumeTrue("Live fuzz replay is opt-in and never makes provider requests", path != null)
        val replies = File(path!!)
        val output = File(System.getenv("LINGUIZE_FUZZ_ENGINE") ?: "build/fuzz/engine.jsonl")
        output.parentFile.mkdirs()
        val prompt = Prompt.load { File("src/main/assets", it).readText() }
        var processed = 0
        output.bufferedWriter().use { writer ->
            replies.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                processed++
                val entry = Json.parseToJsonElement(line).jsonObject
                val case = entry.getValue("case").jsonObject
                val original = case.getValue("text").jsonPrimitive.content
                val language = case.getValue("language").jsonPrimitive.content
                val flags = mutableListOf<String>()
                val actions = mutableListOf<JsonObject>()
                val report = buildJsonObject {
                    put("request_id", case.getValue("request_id"))
                    if ("error" in entry) {
                        put("result", "provider_error")
                        put("error", entry.getValue("error"))
                        return@buildJsonObject
                    }
                    val raw = entry.getValue("verdict").toString()
                    try {
                        val verdict = prompt.parseVerdict(raw)
                        val client = FakeClient { ModelAnswer(verdict, raw) }
                        val checker = fakeChecker(FakePreferences(), client, prompt = prompt)
                        val selected = Language.all.firstOrNull { it.name == language }
                        val checked = checker.check(original, selected)
                        val expectedMessage = "Language: " +
                            (selected?.let { it.name + " (" + it.variety + ")" } ?: "auto") +
                            "\nPunctuation: moderate\nChecks: both\nNative: English (write meaning, assumptions and reasons in English)" +
                            "\nText: <text>" + original + "</text>"
                        if (prompt.userMessage(client.checks.single().third) != expectedMessage)
                            flags += "request_contract_drift"
                        if (checker.reuse(original, selected, raw, checked.context).result != checked.result)
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
                                val revision = result.revision
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
                                        actions += buildJsonObject {
                                            put("id", edit.id); put("kind", kind.token)
                                            put("start", edit.start); put("end", edit.end)
                                            put("from", edit.from); put("to", edit.replacement)
                                            put("why", edit.why?.let(::JsonPrimitive) ?: JsonNull)
                                            put("includes", buildJsonArray { edit.includes.forEach { add(it) } })
                                            put("accepted_text", single.workingText)
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
                }
                writer.appendLine(report.toString())
            }
        }
        assertEquals("Every named-language and Auto Detect request must be replayed", 200, processed)
    }

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

