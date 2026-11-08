package com.evanaronson.linguize.core.corpus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {
    private fun source(name: String, vararg items: Utterance) = object : CorpusSource {
        override val name = name
        override suspend fun utterances() = items.toList()
    }

    private val failing = object : CorpusSource {
        override val name = "broken"
        override suspend fun utterances(): List<Utterance> = error("unreadable")
    }

    @Test
    fun mergesSourcesOldestFirstAndSkipsFailures() = runBlocking {
        val corpus = Corpus(listOf(
            source("chat", Utterance("chat:2", "chat", "es", "hola", at = 20)),
            failing,
            source("check", Utterance("check:1", "check", "ca", "bon dia", at = 10)),
        ))
        assertEquals(listOf("check:1", "chat:2"), corpus.utterances().map { it.id })
        assertEquals(listOf("chat:2"), corpus.utterances("es").map { it.id })
    }

    @Test
    fun languagesAreMostUsedFirst() = runBlocking {
        val corpus = Corpus(listOf(source("check",
            Utterance("check:1", "check", "ca", "a", at = 1),
            Utterance("check:2", "check", "es", "b", at = 2),
            Utterance("check:3", "check", "es", "c", at = 3),
        )))
        assertEquals(listOf("es", "ca"), corpus.languages())
    }

    @Test
    fun correctedSpansAreHalfOpen() {
        // "tinc que anar": the writer accepted a fix for "tinc que" (characters 0 until 8).
        val u = Utterance("check:1", "check", "ca", "tinc que anar", listOf(Span(0, 8)), at = 1)
        assertTrue(u.isCorrected(0, 4))
        assertTrue(u.isCorrected(5, 8))
        assertFalse(u.isCorrected(8, 9))
        assertFalse(u.isCorrected(9, 13))
    }

    @Test(expected = IllegalArgumentException::class)
    fun correctedSpansMustFitTheText() {
        Utterance("check:1", "check", "es", "hola", listOf(Span(0, 9)), at = 1)
    }
}
