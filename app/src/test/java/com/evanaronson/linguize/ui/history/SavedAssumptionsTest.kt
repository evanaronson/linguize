package com.evanaronson.linguize.ui.history

import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Settled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedAssumptionsTest {
    @Test
    fun answeredAssumptionIsCountedOnceAlongsideOpenReadings() {
        val person = Assumption("Person", "One person")
        val time = Assumption("Time", "Tomorrow")
        val answer = Settled(person.about, "Two people")
        val readings = savedAssumptions(listOf(person, time), listOf(answer, Settled.meaning("A group invitation")))

        assertEquals(2, readings.count)
        assertEquals(listOf(time), readings.open)
        assertEquals(listOf(answer), readings.settled)
        assertEquals("Person" to "Two people", readings.first)
    }

    @Test
    fun repeatedLabelsKeepDistinctUnansweredEvidence() {
        val near = Assumption("Place", "Here", words = "this room")
        val far = Assumption("Place", "There", words = "that room")
        val readings = savedAssumptions(listOf(near, far), emptyList())

        assertEquals(2, readings.count)
        assertEquals(listOf(near, far), readings.open)
        assertEquals("Place" to "Here", readings.first)
    }

    @Test
    fun labelAnswersReplaceOriginalReadingsWithoutDiscardingSavedAnswerEvidence() {
        val assumptions = listOf(Assumption("Place", "Here"), Assumption("Place", "There"))
        val answers = listOf(Settled("Place", "At home"), Settled("Place", "At work"))
        val readings = savedAssumptions(assumptions, answers)

        assertEquals(2, readings.count)
        assertEquals(emptyList<Assumption>(), readings.open)
        assertEquals(answers, readings.settled)
    }

    @Test
    fun earlierAnswersRemainVisibleWhenARecheckOmitsTheirOriginalAssumption() {
        val readings = savedAssumptions(emptyList(), listOf(Settled("Time", "Tomorrow")))

        assertEquals(1, readings.count)
        assertEquals("Time" to "Tomorrow", readings.first)
    }

    @Test
    fun wholeMessageClarificationDoesNotBecomeAnAssumption() {
        val readings = savedAssumptions(emptyList(), listOf(Settled.meaning("A group invitation")))

        assertEquals(0, readings.count)
        assertNull(readings.first)
    }
}
