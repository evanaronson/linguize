package com.evanaronson.linguize.ui.tonecheck

import android.app.Application
import com.evanaronson.linguize.core.lab.Landing
import com.evanaronson.linguize.core.lab.LandingRisk
import com.evanaronson.linguize.llm.CheckFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LandsControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val asked = mutableListOf<String>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var failNext: CheckFailure? = null

    private val controller = LandsController(scope) { text ->
        asked += text
        gates[text]?.await()
        failNext?.let { failNext = null; throw it }
        landing(text)
    }

    @After fun stop() = scope.cancel()

    private fun landing(summary: String) = Landing(
        variety = "Spanish (Spain)", summary = summary, risk = LandingRisk.Fine,
        warmth = 3, directness = 3, formality = 3, drivers = emptyList(), notes = emptyList(), versions = emptyList(),
    )

    @Test fun opensOnceForTheSameTextAndShowsTheReading() {
        controller.open("  Hola, ¿qué tal?  ")
        controller.open("Hola, ¿qué tal?")
        val done = controller.state as LandsState.Done
        assertEquals("Hola, ¿qué tal?", done.text)
        assertEquals(listOf("Hola, ¿qué tal?"), asked)
    }

    @Test fun blankTextAsksForNothing() {
        controller.open("   ")
        assertEquals(LandsState.Idle, controller.state)
        assertTrue(asked.isEmpty())
    }

    @Test fun aReadingForTextThatChangedIsDropped() {
        gates["first"] = CompletableDeferred()
        controller.open("first")
        assertEquals(LandsState.Loading("first"), controller.state)
        controller.open("second")
        gates.getValue("first").complete(Unit)
        val done = controller.state as LandsState.Done
        assertEquals("second", done.text)
        // The dropped answer is not kept either: reopening the first text asks again.
        controller.open("first")
        assertEquals(listOf("first", "second", "first"), asked)
    }

    @Test fun earlierReadingsAreShownAgainWithoutAsking() {
        controller.open("one")
        controller.open("two")
        controller.open("one")
        assertEquals("one", (controller.state as LandsState.Done).text)
        assertEquals(listOf("one", "two"), asked)
    }

    @Test fun aFailureCanBeRetriedWithTheSameText() {
        failNext = CheckFailure(CheckFailure.Reason.Offline)
        controller.open("Bon dia")
        val failed = controller.state as LandsState.Failed
        assertEquals(CheckFailure.Reason.Offline, failed.failure.reason)
        controller.retry()
        assertEquals("Bon dia", (controller.state as LandsState.Done).text)
        assertEquals(listOf("Bon dia", "Bon dia"), asked)
    }

    @Test fun unexpectedErrorsBecomeBadResponses() {
        val broken = LandsController(scope) { throw IllegalStateException("boom") }
        broken.open("Hola")
        assertEquals(CheckFailure.Reason.BadResponse, (broken.state as LandsState.Failed).failure.reason)
    }

    @Test fun closingDropsAPendingReading() {
        gates["slow"] = CompletableDeferred()
        controller.open("slow")
        controller.close()
        gates.getValue("slow").complete(Unit)
        assertEquals(LandsState.Idle, controller.state)
    }
}
