package com.evanaronson.linguize.core.writingpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckScheduleTest {
    private val idle = 1500L
    private fun schedule() = CheckSchedule(idle) { it.length >= 3 }

    @Test fun nothingStartsBeforeThePause() {
        val s = schedule()
        s.edited("Bon dia", 1000)
        assertEquals(2500L, s.wakeAt())
        assertNull(s.due(1000))
        assertNull(s.due(2499))
        val start = s.due(2500)!!
        assertEquals("Bon dia", start.request.text)
        assertNull(start.cancelled)
    }

    @Test fun eachKeystrokePushesTheCheckBack() {
        val s = schedule()
        var now = 0L
        for (prefix in listOf("B", "Bo", "Bon", "Bon ", "Bon d", "Bon di", "Bon dia")) {
            s.edited(prefix, now)
            assertNull("typing every 300 ms never pauses long enough", s.due(now))
            now += 300
        }
        assertNull(s.due(now))
        assertEquals(1800L + idle, s.wakeAt())
        assertEquals("Bon dia", s.due(1800 + idle)!!.request.text)
    }

    @Test fun theSameTextIsNotCheckedTwice() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        // Running: no second check of the same text.
        assertNull(s.due(idle * 3))
        assertFalse(s.pending)
        assertTrue(s.finished(first.request))
        assertNull(s.wakeAt())
        assertNull(s.due(idle * 10))
        // Typing and undoing it brings back the checked text: still nothing to do.
        s.edited("Bon diaa", idle * 11)
        assertTrue(s.pending)
        s.edited("Bon dia", idle * 11 + 10)
        assertFalse(s.pending)
        assertNull(s.due(idle * 20))
    }

    @Test fun anEditWhileACheckRunsLetsItFinishButANewerCheckCancelsIt() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        s.edited("Bon dia a tots", idle + 100)
        // The edit alone cancels nothing: the first is still running.
        assertEquals(first.request, s.running)
        val second = s.due(idle * 2 + 100)!!
        assertEquals(first.request, second.cancelled)
        assertEquals("Bon dia a tots", second.request.text)
        // The first answer comes late and is ignored.
        assertFalse(s.finished(first.request))
        assertFalse(s.failed(first.request))
        assertEquals(second.request, s.running)
        assertTrue(s.finished(second.request))
        assertEquals("Bon dia a tots", s.settled)
    }

    @Test fun anAnswerForOlderTextStillCountsWhenNothingNewerStarted() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        s.edited("Bon dia!", idle + 10)
        assertTrue(s.finished(first.request))
        assertEquals("Bon dia", s.settled)
        // The newer text still needs its own check after the pause.
        assertTrue(s.pending)
        assertEquals(idle + 10 + idle, s.wakeAt())
    }

    @Test fun generationsOnlyGrow() {
        val s = schedule()
        val texts = listOf("uno", "dos", "tres", "cuatro")
        val generations = texts.mapIndexed { i, text ->
            s.edited(text, i * 10_000L)
            s.due(i * 10_000L + idle)!!.request.generation
        }
        assertEquals(generations.sorted().distinct(), generations)
    }

    @Test fun aFailedTextWaitsForAnEditOrARetry() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        assertTrue(s.failed(first.request))
        assertEquals("Bon dia", s.failed)
        assertFalse(s.pending)
        assertNull(s.due(idle * 5))
        val retried = s.retry(idle * 5)!!
        assertEquals("Bon dia", retried.request.text)
        assertTrue(s.failed(retried.request))
        s.edited("Bon dia!", idle * 6)
        assertTrue(s.pending)
        assertNotNull(s.due(idle * 7))
    }

    @Test fun textsThatAreNotCheckableNeverStart() {
        val s = schedule()
        s.edited("Bo", 0)
        assertFalse(s.pending)
        assertNull(s.wakeAt())
        assertNull(s.due(idle * 2))
    }

    @Test fun acceptingAChangeOnCheckedTextNeedsNoNewCheck() {
        val s = schedule()
        s.edited("Com estas", 0)
        assertTrue(s.finished(s.due(idle)!!.request))
        s.accepted("Com estas", "Com estàs", idle * 2)
        assertEquals("Com estàs", s.settled)
        assertFalse(s.pending)
        assertNull(s.due(idle * 4))
    }

    @Test fun acceptingOnTextThatWasNeverCheckedStillNeedsACheck() {
        val s = schedule()
        s.edited("Com estas", 0)
        s.accepted("Com estas amic", "Com estàs amic", 10)
        assertTrue(s.pending)
    }

    @Test fun stopCancelsAndMakesTheRunningAnswerStale() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        assertEquals(first.request, s.stop())
        assertNull(s.running)
        assertFalse(s.finished(first.request))
    }

    @Test fun returningToTheTextBeingCheckedDoesNotStartADuplicate() {
        val s = schedule()
        s.edited("Bon dia", 0)
        val first = s.due(idle)!!
        s.edited("Bon diaX", idle + 1)
        s.edited("Bon dia", idle + 2)
        assertNull(s.due(idle * 4))
        assertTrue(s.finished(first.request))
    }
}
