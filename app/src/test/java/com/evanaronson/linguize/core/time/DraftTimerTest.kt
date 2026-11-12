package com.evanaronson.linguize.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftTimerTest {
    private var now = 0L
    private val draft = DraftTimer { now }

    @Test fun writingRunsFromTheFirstKeystrokeToSend() {
        now = 30_000 // the chat was open a while before typing
        draft.typed("")
        now += 1_000
        draft.typed("H")
        now += 4_000
        draft.typed("Hola")
        now += 1_000
        draft.sendTapped()
        now += 20_000 // the check and the card
        assertEquals(DraftTiming(5_000, 0, "m1"), draft.sent("m1"))
    }

    @Test fun keepEditingResumesWritingAndKeepsTheReviewForTheSameMessage() {
        draft.typed("H")
        now += 3_000
        draft.sendTapped()
        now += 15_000
        draft.keptEditing(reviewMs = 12_000)
        now += 2_000
        draft.typed("Hola!")
        draft.sendTapped()
        assertEquals(DraftTiming(5_000, 12_000), draft.sent())
        // The next draft starts afresh.
        draft.typed("x")
        now += 1_000
        assertEquals(DraftTiming(1_000, 0), draft.sent())
    }

    @Test fun offScreenTimeAndLongPausesDontCount() {
        draft.typed("H")
        now += 2_000
        draft.visible(false)
        now += 3_600_000
        draft.visible(true)
        now += 5 * 60_000
        draft.typed("Ho")
        assertEquals(2_000 + ActiveTimer.WRITING_IDLE_CAP_MS, draft.writingMs())
    }

    @Test fun resetDropsTheDraft() {
        draft.typed("H")
        now += 3_000
        draft.reset()
        assertEquals(0L, draft.writingMs())
    }
}
