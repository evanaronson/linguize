package com.evanaronson.linguize.core.corpus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
    fun mergesSuccessfulPortionsOldestFirstAndReportsTheFailedSource() = runBlocking {
        val corpus = Corpus(listOf(
            source("chat", Utterance("chat:2", "chat", "es", "hola", at = 20)),
            failing,
            source("check", Utterance("check:1", "check", "ca", "bon dia", at = 10)),
        ))
        val read = corpus.read()
        assertEquals(CorpusReadStatus.Partial, read.status)
        assertEquals(listOf("check:1", "chat:2"), read.utterances.map { it.id })
        assertEquals(listOf("broken"), read.sources.filter { it.status == CorpusReadStatus.Failed }.map { it.source })
        assertEquals(listOf("chat:2"), corpus.read("es").utterances.map { it.id })
        assertThrows(IOException::class.java) { read.requireComplete() }
        assertTrue(runCatching { corpus.utterances() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { corpus.languages() }.exceptionOrNull() is IOException)
    }

    @Test fun emptyAndFailedReadsAreDifferentEvenAfterLanguageFiltering() = runBlocking {
        assertEquals(CorpusReadStatus.Complete, Corpus(emptyList()).read().status)
        val failed = Corpus(listOf(failing)).read("ca")
        assertEquals(CorpusReadStatus.Failed, failed.status)
        assertTrue(failed.utterances.isEmpty())
        val partial = Corpus(listOf(failing, source("check"))).read("ca")
        assertEquals(CorpusReadStatus.Partial, partial.status)
        assertTrue(partial.utterances.isEmpty())
    }

    @Test fun successfulBoundedCoverageIsCompleteWithoutClaimingTheWholeSourceWasRead() = runBlocking {
        val coverage = CorpusSourceCoverage("check", CorpusReadStatus.Complete, limit = 3000, hasMore = true, revision = 12L)
        val bounded = object : CorpusSource {
            override val name = "check"
            override suspend fun read() = CorpusRead(listOf(Utterance("a", name, "ca", "gat", at = 1)), listOf(coverage))
            override suspend fun utterances() = read().requireComplete()
        }
        val read = Corpus(listOf(bounded)).read()
        assertEquals(CorpusReadStatus.Complete, read.status)
        assertEquals(listOf(coverage), read.sources)
        assertEquals(listOf("a"), read.requireComplete().map { it.id })
    }

    @Test fun aPartialSourceCannotBecomeCompleteWhenItsAvailableRowsAreFilteredOut() = runBlocking<Unit> {
        val incomplete = object : CorpusSource {
            override val name = "partial"
            override suspend fun read() = CorpusRead(listOf(Utterance("a", name, "es", "casa", at = 1)),
                listOf(CorpusSourceCoverage(name, CorpusReadStatus.Partial, limit = 10)))
            override suspend fun utterances() = read().requireComplete()
        }
        val read = Corpus(listOf(incomplete)).read("ca")
        assertEquals(CorpusReadStatus.Partial, read.status)
        assertTrue(read.utterances.isEmpty())
        assertThrows(IOException::class.java) { read.requireComplete() }
    }

    @Test fun completeMergingKeepsFirstSourceIdentityThenOrdersByTime() = runBlocking {
        val first = Utterance("duplicate", "first", "es", "first", at = 20)
        val corpus = Corpus(listOf(source("first", first), source("second",
            first.copy(source = "second", text = "later copy", at = 1),
            Utterance("oldest", "second", "ca", "gat", at = 10))))
        assertEquals(listOf("oldest", "duplicate"), corpus.utterances().map { it.id })
        assertEquals(first, corpus.utterances().last())
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

    @Test
    fun identityLemmatizerKnowsNoPartOfSpeech() {
        assertEquals(setOf(Lexeme("llama", Pos.Unknown)), Lemmatizer.Identity.lexemes("llama", "es"))
        assertEquals(setOf("llama"), Lemmatizer.Identity.lemmas("llama", "es"))
    }

    @Test
    fun lemmasDropPartOfSpeechButKeepHomographsApart() {
        val l = Lemmatizer { _, _ -> setOf(Lexeme("el", Pos.Article), Lexeme("ella", Pos.Pronoun), Lexeme("el", Pos.Pronoun)) }
        assertEquals(setOf("el", "ella"), l.lemmas("la", "es"))
        assertEquals(3, l.lexemes("la", "es").size)
    }

    @Test
    fun posTokensMatchTheSpanishDeck() {
        listOf("n", "v", "adj", "adv", "pron", "prep", "conj", "num", "interj", "art").forEach {
            assertEquals(it, Pos.of(it).token)
        }
        assertEquals(Pos.Determiner, Pos.of("determiner"))
        assertEquals(Pos.Other, Pos.of("other"))
        assertEquals(Pos.Unknown, Pos.of("zzz"))
    }
}
