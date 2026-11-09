package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Punctuation
import com.evanaronson.linguize.llm.CheckRequest
import com.evanaronson.linguize.llm.Prompt
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lab: one check carries the gap rules, the tone rules and, in the chat, the reply context, always in one order. */
class CheckCompositionTest {
    private fun read(name: String) = File("src/main/assets/$name").readText()
    private val base = Prompt.load(::read)
    private val gaps = GapsInCheck.load(::read)
    private val tone = ToneInCheck.load(::read)
    private val reply = ReplyPrompt.load(::read)

    @Test
    fun theOrderIsShippedThenGapsThenToneThenTheReply() {
        val composed = CheckComposition.prompt(base, gaps, tone)
        val system = reply.system(composed)
        val positions = listOf(base.system.trimEnd(), gaps.addendum.trim(), tone.addendum.trim(), reply.addendum.trim())
            .map { system.indexOf(it) }
        assertEquals(0, positions.first())
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it >= 0 })
        // The same inputs always compose to the same request.
        assertEquals(system, reply.system(CheckComposition.prompt(base, gaps, tone)))
    }

    @Test
    fun theUserMessageIsTheShippedOneWithTheReplyLineInFront() {
        val composed = CheckComposition.prompt(base, gaps, tone)
        val request = CheckRequest("Sí, a las 5", null, Punctuation.Moderate, Judgments.Both)
        assertEquals(base.userMessage(request), composed.userMessage(request))
        val generation = reply.generation(composed, request, "¿Le va bien el martes?")
        assertEquals(ReplyPrompt.LINE_PREFIX + "{\"message\":\"¿Le va bien el martes?\"}\n" + base.userMessage(request), generation.user)
        assertTrue("landing" in (generation.schema["properties"] as JsonObject))
    }

    @Test
    fun onlyTheToneRulesTouchTheSchema() {
        assertEquals(base.schema, CheckComposition.prompt(base, gaps, null).schema)
        assertEquals(tone.schema(base.schema), CheckComposition.prompt(base, gaps, tone).schema)
        assertSame(base, CheckComposition.prompt(base, null, null))
    }

    @Test
    fun theHashNamesEveryPartInOrder() {
        val replyHash = reply.hash(base, "Hola")
        val hash = CheckComposition.hash(base, gaps, tone, replyHash)
        assertEquals(tone.hash(gaps.hash(replyHash)), hash)
        assertTrue(Regex("""^${base.hash}-r[0-9a-f]{8}-g[0-9a-f]{8}-t[0-9a-f]{8}$""").matches(hash))
        assertEquals(tone.hash(gaps.hash(base.hash)), CheckComposition.hash(base, gaps, tone))
        assertEquals(base.hash, CheckComposition.hash(base, null, null))
        // With and without the tone rules, answers are never reused for each other.
        assertTrue(CheckComposition.hash(base, gaps, null) != CheckComposition.hash(base, gaps, tone))
    }

    @Test
    fun theAddendaKeepPromptGrowthModest() {
        // Gaps, tone and the reply rules together stay well under half the shipped prompt.
        val added = gaps.addendum.length + tone.addendum.length + reply.addendum.length + read(ToneInCheck.SCHEMA_ASSET).length
        assertTrue("Lab rules add $added characters", added < base.system.length / 2)
    }
}
