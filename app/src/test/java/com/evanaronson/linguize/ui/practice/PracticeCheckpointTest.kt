package com.evanaronson.linguize.ui.practice

import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.Hint
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.core.practice.ReviewState
import com.evanaronson.linguize.core.practice.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checkpoints rebuild source text and reconcile saved decisions with the durable schedule. */
class PracticeCheckpointTest {
    private val a = item("a")
    private val b = item("b")
    private val c = item("c")
    private val now = 1_000_000_000_000L
    private val today = 11_574L

    private fun item(id: String) = PracticeItem(
        id, "source-$id", 10, "ca", "Private sentence: venen despres.",
        wrongStart = 18, wrongEnd = 31, right = "com vénen després",
        why = "Private explanation for $id",
    )

    private fun run(vararg items: PracticeItem, before: Map<String, ReviewState?> = emptyMap()) = PracticeRun(
        items.toList(), id = "stable-run", language = "ca",
        baseline = items.associate { it.id to before[it.id] }, correctFirst = true,
    )

    private fun read(run: PracticeRun, pending: PracticeGrade? = null): PracticeCheckpoint =
        PracticeCheckpoint.read(PracticeCheckpoint(entry = "stable-page", run = SavedPracticeRun.of(run), pending = pending).encode())!!

    @Test fun checkpointRoundTripStoresIdentityAndDecisionsWithoutCopyingSourceText() {
        val feedback = Answered(AnswerResult.Almost(Hint.MissingAccent("vénen")))
        val answered = run(a).answer(feedback).first
        val pending = PracticeGrade(a.id, Grade.Hard.name, now, today, null)
        val checkpoint = PracticeCheckpoint(entry = "stable-page", run = SavedPracticeRun.of(answered), pending = pending)
        val encoded = checkpoint.encode()

        for (privateText in listOf(a.sentence, a.right, a.why!!, "vénen", a.sessionId)) {
            assertFalse("Checkpoint copied source content: $privateText", encoded.contains(privateText))
        }
        assertEquals(checkpoint, PracticeCheckpoint.read(encoded))
        assertEquals(checkpoint, PracticeCheckpoint.read(encoded.dropLast(1) + ",\"future_field\":123}"))

        val rebuilt = a.copy(sentence = a.sentence.replace("Private", "Changed"), why = "Freshly rebuilt explanation")
        val restored = read(answered, pending).run!!.restore(listOf(rebuilt), PracticeProgress(), pending)
        assertSame(rebuilt, restored.run!!.current)
        assertEquals(feedback, restored.run.answered)
        assertEquals(pending, restored.pending)
        assertEquals(true, restored.run.correctFirst)
    }

    @Test fun everyFeedbackKindRoundTripsAndAccentWordsAreRebuiltByPosition() {
        val feedback = listOf(
            Answered(AnswerResult.Correct, pickedCorrect = true),
            Answered(AnswerResult.Wrong, pickedCorrect = false),
            Answered(AnswerResult.Wrong, revealed = true),
            Answered(AnswerResult.Unchanged),
            Answered(AnswerResult.Almost(Hint.MissingAccent("vénen"))),
            Answered(AnswerResult.Almost(Hint.ExtraAccent("com"))),
            Answered(AnswerResult.Almost(Hint.WrongAccent("després"))),
            Answered(AnswerResult.Almost(Hint.Accents)),
            Answered(AnswerResult.Almost(Hint.Punctuation)),
            Answered(AnswerResult.Almost(Hint.Capitals)),
            Answered(AnswerResult.Almost(Hint.Spelling)),
        )
        for (answer in feedback) {
            val decoded = read(run(a).answer(answer).first).run!!.answered!!
            assertEquals(answer, decoded.read(a))
        }

        val savedHint = SavedPracticeAnswer.of(Answered(AnswerResult.Almost(Hint.MissingAccent("vénen"))), a)
        assertEquals(1, savedHint.word)
        assertEquals(
            Answered(AnswerResult.Almost(Hint.MissingAccent("vendrán"))),
            savedHint.read(a.copy(right = "ellos vendrán después")),
        )
        assertEquals(Answered(AnswerResult.Almost(Hint.Accents)), savedHint.read(a.copy(right = "single")))
    }

    @Test fun malformedVersionsAndInconsistentRunCommandsAreRejected() {
        val answered = run(a).answer(Answered(AnswerResult.Correct)).first
        val saved = SavedPracticeRun.of(answered)
        val pending = PracticeGrade(a.id, Grade.Good.name, now, today, null)
        val valid = PracticeCheckpoint(entry = "page", run = saved, pending = pending)
        assertNotNull(PracticeCheckpoint.read(valid.encode()))
        assertNull(PracticeCheckpoint.read(null))
        assertNull(PracticeCheckpoint.read("{broken"))
        val invalid = listOf(
            valid.copy(version = 2),
            valid.copy(run = saved.copy(id = "")),
            valid.copy(run = saved.copy(index = -1)),
            valid.copy(run = saved.copy(index = 2)),
            valid.copy(run = saved.copy(baseline = emptyMap())),
            valid.copy(run = saved.copy(grades = mapOf(a.id to "not-a-grade"))),
            valid.copy(run = saved.copy(grades = saved.grades + ("absent" to Grade.Good.name))),
            valid.copy(run = saved.copy(queue = listOf(a.id, a.id), repeated = emptySet())),
            valid.copy(run = saved.copy(repeated = setOf(a.id))),
            valid.copy(run = saved.copy(answered = null)),
            valid.copy(pending = pending.copy(id = b.id)),
            valid.copy(pending = pending.copy(grade = Grade.Again.name)),
            valid.copy(pending = pending.copy(grade = "unknown")),
            valid.copy(run = null),
        )
        for (checkpoint in invalid) assertNull(checkpoint.encode(), PracticeCheckpoint.read(checkpoint.encode()))
    }

    @Test fun missingEarlierItemsRemapTheCursorAndKeepExactlyOneRepeat() {
        var original = run(a, b, c)
        original = original.answer(Answered(AnswerResult.Wrong)).first.next()
        original = original.answer(Answered(AnswerResult.Correct)).first.next()
        original = original.answer(Answered(AnswerResult.Correct)).first.next().copy(correctFirst = false)
        val progress = PracticeProgress(states = mapOf(
            a.id to Schedule.review(null, Grade.Again, now),
            b.id to Schedule.review(null, Grade.Good, now),
            c.id to Schedule.review(null, Grade.Good, now),
        ))

        // Input order comes from the checkpoint, not the newly rebuilt History ordering.
        val restored = read(original).run!!.restore(listOf(c, a), progress, null).run!!
        assertEquals(listOf(a.id, c.id, a.id), restored.queue.map { it.id })
        assertEquals(2, restored.index)
        assertEquals(a, restored.current)
        assertEquals(setOf(a.id), restored.repeated)
        assertEquals(mapOf(a.id to Grade.Again, c.id to Grade.Good), restored.grades)
        assertEquals(2, restored.size)
        assertEquals(1, restored.right)
        assertEquals(false, restored.correctFirst)
        val (answeredAgain, secondGrade) = restored.answer(Answered(AnswerResult.Correct))
        assertNull(secondGrade)
        assertTrue(answeredAgain.next().finished)
        assertEquals(Grade.Again, answeredAgain.grades[a.id])
    }

    @Test fun deletingTheCurrentItemDropsItsFeedbackAndPendingGradeWithoutSkippingTheNextItem() {
        var original = run(a, b, c).answer(Answered(AnswerResult.Correct)).first.next()
        original = original.answer(Answered(AnswerResult.Wrong, revealed = true)).first
        val pending = PracticeGrade(b.id, Grade.Again.name, now, today, null)
        val progress = PracticeProgress(states = mapOf(a.id to Schedule.review(null, Grade.Good, now)))

        val restored = read(original, pending).run!!.restore(listOf(c, a), progress, pending)
        assertEquals(listOf(a.id, c.id), restored.run!!.queue.map { it.id })
        assertEquals(1, restored.run.index)
        assertEquals(c, restored.run.current)
        assertNull(restored.run.answered)
        assertNull(restored.pending)
        assertEquals(setOf(a.id), restored.run.grades.keys)
        assertSame(restored.run, restored.run.next())
    }

    @Test fun advancedUnansweredItemsAreOmittedWithoutInventingGradesOrChangingTheBaseline() {
        val baseline = Schedule.review(null, Grade.Good, now - Schedule.DAY)
        val original = run(a, b, c, before = mapOf(a.id to baseline))
        val progress = PracticeProgress(states = mapOf(
            a.id to Schedule.review(baseline, Grade.Good, now),
            b.id to Schedule.review(null, Grade.Again, now),
        ))

        val saved = read(original).run!!
        val restored = saved.restore(listOf(a, b, c), progress, null).run!!
        assertEquals(listOf(c.id), restored.queue.map { it.id })
        assertEquals(0, restored.index)
        assertTrue(restored.grades.isEmpty())
        assertEquals(0, restored.right)
        assertEquals(original.id, restored.id)
        assertEquals(mapOf(c.id to null), restored.baseline)
        assertEquals(baseline, saved.baseline[a.id])
        assertEquals(baseline, original.baseline[a.id])
    }

    @Test fun anAcknowledgedGradeKeepsKnownFeedbackAndDoesNotBecomePendingAgain() {
        val baseline = Schedule.review(null, Grade.Good, now - Schedule.DAY)
        val answer = Answered(AnswerResult.Almost(Hint.MissingAccent("vénen")))
        val original = run(a, before = mapOf(a.id to baseline)).answer(answer).first
        val pending = PracticeGrade(a.id, Grade.Hard.name, now, today, baseline)
        val progress = PracticeProgress(states = mapOf(a.id to Schedule.review(baseline, Grade.Hard, now)))

        for (savedPending in listOf(pending, null)) {
            val restored = read(original, savedPending).run!!.restore(listOf(a), progress, savedPending)
            assertEquals(answer, restored.run!!.answered)
            assertEquals(mapOf(a.id to Grade.Hard), restored.run.grades)
            assertEquals(baseline, restored.run.baseline[a.id])
            assertEquals(true, restored.run.correctFirst)
            assertNull(restored.pending)
        }
        assertEquals(2, progress.states.getValue(a.id).reviews)
    }

    @Test fun anUncommittedGradeRetainsItsOriginalScheduleTimeAndDayForRetry() {
        val baseline = Schedule.review(null, Grade.Good, now - Schedule.DAY)
        val original = run(a, before = mapOf(a.id to baseline))
            .answer(Answered(AnswerResult.Wrong, revealed = true)).first
        val pending = PracticeGrade(a.id, Grade.Again.name, now, today, baseline)
        val progress = PracticeProgress(states = mapOf(a.id to baseline), newDay = today + 1, newToday = 3)

        val restored = read(original, pending).run!!.restore(listOf(a), progress, pending)
        assertEquals(pending, restored.pending)
        assertEquals(now, restored.pending!!.now)
        assertEquals(today, restored.pending.today)
        assertTrue(restored.run!!.answered!!.revealed)
        assertEquals(1, progress.states.getValue(a.id).reviews)
        assertEquals(3, progress.newToday)
    }

    @Test fun aSupersededPendingGradeLeavesAFinishedEmptyMarkerForTheSameRun() {
        val original = run(a).answer(Answered(AnswerResult.Wrong, revealed = true)).first
        val pending = PracticeGrade(a.id, Grade.Again.name, now, today, null)
        val newer = Schedule.review(Schedule.review(null, Grade.Good, now), Grade.Good, now + 1)
        val progress = PracticeProgress(states = mapOf(a.id to newer))

        val restored = read(original, pending).run!!.restore(listOf(a), progress, pending)
        val finished = restored.run!!
        assertEquals(original.id, finished.id)
        assertTrue(finished.finished)
        assertTrue(finished.queue.isEmpty())
        assertEquals(0, finished.size)
        assertEquals(0, finished.right)
        assertNull(finished.answered)
        assertNull(restored.pending)
        assertNotNull(read(finished).run)
    }

    @Test fun aCompletedRunRetainsItsKnownScoreAndDoesNotReopenAnItem() {
        val original = run(a, b)
            .answer(Answered(AnswerResult.Correct)).first.next()
            .answer(Answered(AnswerResult.Almost(Hint.Punctuation))).first.next()
        val progress = PracticeProgress(states = mapOf(
            a.id to Schedule.review(null, Grade.Good, now),
            b.id to Schedule.review(null, Grade.Hard, now),
        ))

        val restored = read(original).run!!.restore(listOf(b, a), progress, null).run!!
        assertTrue(restored.finished)
        assertEquals(2, restored.index)
        assertEquals(2, restored.size)
        assertEquals(2, restored.right)
        assertNull(restored.current)
    }
}
