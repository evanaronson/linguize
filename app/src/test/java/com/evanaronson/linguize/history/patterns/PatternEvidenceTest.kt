package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternEvidenceTest {
    private val details = (1..7).map { check("c$it", it, fix("esta", "está")) }

    @Test
    fun readsVisibleChecksNewestFirst() = runBlocking {
        val store = FakePatternHistory(details)
        val read = store.patternEvidence()!!
        assertEquals((7 downTo 1).map { "c$it" }, read.map { it.session.id })
    }

    @Test
    fun stopsAtTheLimit() = runBlocking {
        val store = FakePatternHistory(details)
        val read = store.patternEvidence(limit = 4)!!
        assertEquals(listOf("c7", "c6", "c5", "c4"), read.map { it.session.id })
        assertEquals(4, store.detailReads.size)
    }

    @Test
    fun aFailedReadGivesNothingRatherThanPartialEvidence() = runBlocking {
        assertNull(FakePatternHistory(details).apply { failPage = true }.patternEvidence())
        assertNull(FakePatternHistory(details).apply { failDetail = "c3" }.patternEvidence())
    }

    @Test
    fun aCheckDeletedMidReadIsSkipped() = runBlocking {
        val store = FakePatternHistory(details).apply { vanishOnDetail = "c5" }
        val read = store.patternEvidence()!!
        assertEquals(6, read.size)
        assertEquals(false, read.any { it.session.id == "c5" })
    }

    @Test
    fun emptyHistoryReadsNothing() = runBlocking {
        assertEquals(emptyList<Any>(), FakePatternHistory().patternEvidence())
    }
}
