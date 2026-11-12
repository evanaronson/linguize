package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.patterns.PatternFixtures.DAY
import com.evanaronson.linguize.history.patterns.PatternFixtures.Fix
import com.evanaronson.linguize.history.patterns.PatternFixtures.check
import com.evanaronson.linguize.history.patterns.PatternFixtures.fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternAnalyserTest {
    @Test
    fun groupsRecurringFixesAcrossChecksWithTheirSources() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("a", 1, fix("esta", "está"), fix("tambien", "también")),
                check("b", 2, fix("es cansado", "está cansado")),
                check("c", 3, fix("Esta", "Está"), fix("estoy profesor", "soy profesor")),
            ),
        )
        assertEquals(PatternAnalyser.VERSION, report.analyser)
        assertEquals(3, report.checksCounted)
        val accents = report.pattern(PatternKind.Accents)!!
        assertEquals(3, accents.occurrences)
        assertEquals(2, accents.checks)
        assertEquals(listOf("c-s0", "a-s0", "a-s1"), accents.examples.map { it.suggestionId })
        assertEquals(setOf(Basis.Exact), accents.bases)
        assertFalse(accents.inferred)
        val serEstar = report.pattern(PatternKind.SerEstar)!!
        assertEquals(listOf("c", "b"), serEstar.examples.map { it.sessionId })
        assertTrue(serEstar.inferred)
        // Every counted check is named, with exactly the rows it contributed.
        assertEquals(
            listOf(PatternSource("c", 0, listOf("c-s0", "c-s1")), PatternSource("b", 0, listOf("b-s0")), PatternSource("a", 0, listOf("a-s0", "a-s1"))),
            report.sources,
        )
        assertEquals(setOf("a", "b", "c"), report.evidenceSessions)
    }

    @Test
    fun onlyAcceptedChangesAreEvidence() {
        val others = listOf(Decision.Ignored, Decision.Undone, Decision.Copied, Decision.Retired, Decision.Superseded)
        val details = others.mapIndexed { index, decision ->
            check("x$index", index, Fix("esta", "está", decision = decision))
        }
        val report = PatternAnalyser.analyse(details)
        assertTrue(report.patterns.isEmpty())
        assertTrue(report.unpatterned.isEmpty())
        assertTrue(report.sources.all { it.suggestionIds.isEmpty() })
        assertTrue(report.evidenceSessions.isEmpty())
    }

    @Test
    fun aGroupInOneCheckIsNotAPattern() {
        val report = PatternAnalyser.analyse(listOf(check("a", 1, fix("esta", "está"), fix("tenia", "tenía"), fix("mas", "más"))))
        assertTrue(report.patterns.isEmpty())
        assertEquals(3, report.unpatterned.size)
        assertTrue(report.unpatterned.all { it.kind == PatternKind.Accents })
    }

    @Test
    fun unrecognisedFixesAreNeverAPattern() {
        val report = PatternAnalyser.analyse((1..4).map { check("c$it", it, fix("coger el bus", "tomar el bus")) })
        assertNull(report.pattern(PatternKind.Other))
        assertEquals(4, report.unpatterned.size)
        assertTrue(report.unpatterned.all { it.kind == PatternKind.Other })
    }

    @Test
    fun reusedAnswersAreNotCountedAgain() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("orig", 1, fix("esta", "está"), text = "a"),
                check("reuse", 2, fix("esta", "está"), text = "b", reusedFrom = "orig"),
            ),
        )
        assertEquals(1, report.reused)
        assertEquals(1, report.checksCounted)
        assertTrue(report.patterns.isEmpty())
        assertEquals(listOf("orig"), report.sources.map { it.sessionId })
    }

    @Test
    fun theSameTextCheckedAgainCountsOnceAsTheNewest() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("old", 1, fix("esta", "está"), text = "same"),
                check("new", 5, fix("esta", "está"), text = "same"),
            ),
        )
        assertEquals(1, report.repeats)
        assertEquals(listOf("new"), report.sources.map { it.sessionId })
        assertTrue(report.patterns.isEmpty())
    }

    @Test
    fun deletingACheckRemovesItsEvidenceAndChangesTheFingerprint() {
        val all = listOf(
            check("a", 1, fix("esta", "está")),
            check("b", 2, fix("tenia", "tenía")),
            check("c", 3, fix("aqui", "aquí")),
        )
        val before = PatternAnalyser.analyse(all)
        val after = PatternAnalyser.analyse(all.filterNot { it.session.id == "b" })
        assertEquals(3, before.pattern(PatternKind.Accents)!!.occurrences)
        assertEquals(2, after.pattern(PatternKind.Accents)!!.occurrences)
        assertFalse(after.sources.any { it.sessionId == "b" })
        assertFalse(after.patterns.flatMap { it.examples }.any { it.sessionId == "b" })
        assertNotEquals(before.fingerprint, after.fingerprint)

        val gone = PatternAnalyser.analyse(all.filter { it.session.id == "a" })
        assertTrue(gone.patterns.isEmpty())
        assertFalse(gone.worthShowing)
    }

    @Test
    fun fingerprintIgnoresReadOrderButNotEvidence() {
        val all = listOf(check("a", 1, fix("esta", "está")), check("b", 2, fix("tenia", "tenía")))
        assertEquals(PatternAnalyser.analyse(all).fingerprint, PatternAnalyser.analyse(all.reversed()).fingerprint)
        val undone = listOf(all[0], check("b", 2, Fix("tenia", "tenía", decision = Decision.Undone)))
        assertNotEquals(PatternAnalyser.analyse(all).fingerprint, PatternAnalyser.analyse(undone).fingerprint)
    }

    @Test
    fun analysisIsRepeatable() {
        val all = (1..12).map { check("c$it", it, fix("esta", "está"), fix("la problema", "el problema")) }
        assertEquals(PatternAnalyser.analyse(all), PatternAnalyser.analyse(all.shuffled(java.util.Random(4))))
    }

    @Test
    fun patternsAreOrderedByChecksThenOccurrences() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("a", 1, fix("es cansado", "está cansado"), fix("esta", "está"), fix("tenia", "tenía")),
                check("b", 2, fix("es cansado", "está cansado"), fix("esta", "está")),
                check("c", 3, fix("estoy profesor", "soy profesor")),
            ),
        )
        assertEquals(listOf(PatternKind.SerEstar, PatternKind.Accents), report.patterns.map { it.kind })
    }

    @Test
    fun repeatedPairsAreMergedAsTheWriterWouldSeeThem() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("a", 1, fix("Esta ", "Está")),
                check("b", 2, fix("esta", "está")),
                check("c", 3, fix("tenia", "tenía")),
            ),
        )
        assertEquals(
            listOf(PairCount("esta", "está", 2), PairCount("tenia", "tenía", 1)),
            report.pattern(PatternKind.Accents)!!.pairs,
        )
    }

    @Test
    fun eachChangeUsesItsOwnAttemptLanguage() {
        val catalan = check("ca1", 1, fix("tinc que anar", "he d'anar"), language = "ca")
        val catalan2 = check("ca2", 2, fix("tenim que parlar", "hem de parlar"), language = "ca")
        val spanish = check("es1", 3, fix("tinc que anar", "he d'anar"), language = "es")
        val report = PatternAnalyser.analyse(listOf(catalan, catalan2, spanish))
        val tenir = report.pattern(PatternKind.TenirQue)!!
        assertEquals(listOf("ca2", "ca1"), tenir.examples.map { it.sessionId })
        assertEquals(listOf("ca", "ca"), tenir.examples.map { it.language })
        assertEquals(PatternKind.Other, report.unpatterned.single().kind)
    }

    @Test
    fun rewordingsCountOnlyForSpecificRules() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("a", 1, Fix("coger", "tomar", kind = EditKind.Natural), Fix("es cansado", "está cansado", kind = EditKind.Natural)),
                check("b", 2, Fix("coger", "tomar", kind = EditKind.Natural), fix("era lejos", "estaba lejos")),
            ),
        )
        assertEquals(listOf(PatternKind.SerEstar), report.patterns.map { it.kind })
        assertTrue(report.unpatterned.isEmpty())
        assertEquals(listOf("a-s1"), report.sources.first { it.sessionId == "a" }.suggestionIds)
    }

    @Test
    fun trendComparesSharesOfOlderAndNewerChecks() {
        // Ten checks; accents in the four oldest, none of the newest: fewer lately.
        val fewer = (1..10).map { day -> if (day <= 4) check("c$day", day, fix("esta", "está")) else check("c$day", day, fix("coger", "tomar")) }
        assertEquals(Trend.Fewer, PatternAnalyser.analyse(fewer).pattern(PatternKind.Accents)!!.trend)

        val more = (1..10).map { day -> if (day > 6) check("c$day", day, fix("esta", "está")) else check("c$day", day, fix("coger", "tomar")) }
        assertEquals(Trend.More, PatternAnalyser.analyse(more).pattern(PatternKind.Accents)!!.trend)

        val steady = (1..12).map { day -> if (day % 2 == 0) check("c$day", day, fix("esta", "está")) else check("c$day", day, fix("coger", "tomar")) }
        assertEquals(Trend.Steady, PatternAnalyser.analyse(steady).pattern(PatternKind.Accents)!!.trend)
    }

    @Test
    fun trendWaitsForEnoughChecks() {
        val few = (1..5).map { check("c$it", it, fix("esta", "está")) }
        assertEquals(Trend.Unknown, PatternAnalyser.analyse(few).pattern(PatternKind.Accents)!!.trend)
        // Plenty of checks but the group appears in only two of them.
        val rare = (1..12).map { day -> if (day <= 2) check("c$day", day, fix("esta", "está")) else check("c$day", day) }
        assertEquals(Trend.Unknown, PatternAnalyser.analyse(rare).pattern(PatternKind.Accents)!!.trend)
    }

    @Test
    fun moreChecksAloneDoNotReadAsMore() {
        // Twice as many checks lately, with the same share containing accents.
        val older = (1..4).map { day -> check("o$day", day, if (day % 2 == 0) fix("esta", "está") else fix("coger", "tomar")) }
        val newer = (1..8).map { n -> check("n$n", 10 + n, if (n % 2 == 0) fix("esta", "está") else fix("coger", "tomar")) }
        val report = PatternAnalyser.analyse(older + newer)
        assertEquals(Trend.Steady, report.pattern(PatternKind.Accents)!!.trend)
    }

    @Test
    fun bucketsSpreadOccurrencesOverTimeOldestFirst() {
        val report = PatternAnalyser.analyse(
            listOf(
                check("a", 0, fix("esta", "está"), fix("tenia", "tenía")),
                check("b", 3, fix("aqui", "aquí")),
                check("c", 6, fix("mas", "más")),
            ),
        )
        val buckets = report.pattern(PatternKind.Accents)!!.buckets
        assertEquals(PatternAnalyser.BUCKETS, buckets.size)
        assertEquals(4, buckets.sum())
        assertEquals(2, buckets.first())
        assertEquals(1, buckets.last())
        assertEquals(0L, report.firstAt)
        assertEquals(6 * DAY, report.lastAt)
    }

    @Test
    fun worthShowingNeedsAFewChecksAndAPattern() {
        val two = listOf(check("a", 1, fix("esta", "está")), check("b", 2, fix("tenia", "tenía")))
        assertFalse(PatternAnalyser.analyse(two).worthShowing)
        val three = two + check("c", 3)
        assertTrue(PatternAnalyser.analyse(three).worthShowing)
    }

    @Test
    fun emptyHistoryGivesAnEmptyReport() {
        val report = PatternAnalyser.analyse(emptyList())
        assertEquals(0, report.checksRead)
        assertTrue(report.patterns.isEmpty())
        assertNull(report.firstAt)
        assertFalse(report.worthShowing)
    }
}
