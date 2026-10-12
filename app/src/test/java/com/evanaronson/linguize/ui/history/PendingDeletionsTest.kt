package com.evanaronson.linguize.ui.history

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingDeletionsTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("pending-test", Context.MODE_PRIVATE)

    @Test
    fun aSecondHomeDoesNotCommitTheFirstHomesUndo() {
        val first = PendingDeletions(prefs, "current")
        assertTrue(first.recover().isEmpty())
        first.add("undoable")
        val second = PendingDeletions(prefs, "current")
        assertTrue(second.recover().isEmpty())
        first.remove("undoable")
        assertTrue(PendingDeletions(prefs, "next-process").recover().isEmpty())
    }

    @Test
    fun aRestartRecoversEveryPendingDeletionWhileCompletionRemovesOnlyItsOwnNote() {
        val first = PendingDeletions(prefs, "current")
        first.recover()
        first.add("first")
        first.add("second")
        first.add("third")
        first.remove("second")
        val next = PendingDeletions(prefs, "next-process")
        assertEquals(setOf("first", "third"), next.recover())
        assertTrue(next.recover().isEmpty())
        next.add("new-undo")
        next.remove("first")
        next.remove("third")
        assertEquals(setOf("new-undo"), PendingDeletions(prefs, "third-process").recover())
    }

    @Test
    fun aDeletionFromAnOlderVersionIsRecoveredOnce() {
        prefs.edit().putString("pendingDelete", "legacy").apply()
        val current = PendingDeletions(prefs, "current")
        assertEquals(setOf("legacy"), current.recover())
        current.remove("legacy")
        assertTrue(PendingDeletions(prefs, "next-process").recover().isEmpty())
    }

    @Test
    fun undoInOneHomeCannotEraseAnotherHomesPendingDeletionOfTheSameRow() {
        val first = PendingDeletions(prefs, "current")
        val second = PendingDeletions(prefs, "current")
        first.recover()
        second.recover()
        first.add("same-row")
        second.add("same-row")
        first.remove("same-row")
        assertEquals(setOf("same-row"), PendingDeletions(prefs, "next-process").recover())
    }
}
