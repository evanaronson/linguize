package com.evanaronson.linguize.ui.time

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.time.SpeedTrend
import com.evanaronson.linguize.core.time.TimeRecord
import com.evanaronson.linguize.ui.theme.LinguizeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lab, Time spent: what Speed over time says. Each check is a freshly composed section (no
 * recomposition after an event), so it doesn't depend on the shared frame clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeedOverTimeTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    private val activity = controller.get()
    private val compose = com.evanaronson.linguize.testing.OwnComposition(activity)
    private val zone = ZoneId.systemDefault()
    private val now = LocalDate.of(2026, 11, 11).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val week = 7 * 86_400_000L
    private var ids = 0

    @After fun tearDown() {
        compose.dispose()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun week(weeksAgo: Int, msPerWord: Long, language: String = "es", rules: Int = 2, count: Int = 5) = List(count) {
        TimeRecord("c${ids++}", "chat", language, msPerWord * 10, 12_000, 10, 2, 1, now - weeksAgo * week, rules)
    }

    @Test fun saysTheTrendAndReadsOutEveryPoint() {
        show(SpeedTrend.of(week(7, 4_000) + week(0, 3_200), now, zone, "es"))
        assertTrue(hasText("You're writing about 20% faster than 7 weeks ago"))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_writing)))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_review)))
        assertFalse("Scoped: no most-written line", texts().any { it.contains("the language you write most") })

        val writing = descriptions().single { it.startsWith("Writing, seconds per word by week, lower is faster: ") }
        assertTrue(writing, writing.contains(": 4.0 s, "))
        assertTrue(writing, writing.endsWith(": 3.2 s"))
        assertEquals("Six thin weeks between", 6, Regex("too few messages").findAll(writing).count())
        val review = descriptions().single { it.startsWith("Reviewing, seconds per change by week, lower is faster: ") }
        assertTrue(review, review.endsWith(": 6 s"))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_thin_legend)))
    }

    @Test fun aboutTheSameAndSlower() {
        show(SpeedTrend.of(week(4, 4_000) + week(0, 4_200), now, zone, "es"))
        assertTrue(hasText("About the same speed as 4 weeks ago"))
        show(SpeedTrend.of(week(4, 4_000) + week(0, 5_000), now, zone, "es"))
        assertTrue(hasText("You're writing about 25% slower than 4 weeks ago"))
    }

    @Test fun saysNothingWithoutEnough() {
        show(SpeedTrend.of(week(0, 3_000), now, zone, "es"))
        assertFalse(texts().any { it.startsWith("You're writing") || it.startsWith("About the same") })
        assertTrue("The point still shows", descriptions().any { it.endsWith(": 3.0 s") })
    }

    @Test fun underLinguizeNamesTheLanguageAndTheLeftOutRows() {
        show(SpeedTrend.of(week(6, 9_000, rules = 1) + week(0, 3_000, language = "ca"), now, zone, null))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_dominant, "Catalan")))
        assertFalse("v1 Spanish rows don't make Spanish the language", hasText(activity.getString(R.string.lab_time_spent_speed_left_out)))

        show(SpeedTrend.of(week(6, 9_000, rules = 1) + week(0, 3_000), now, zone, "es"))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_left_out)))
    }

    @Test fun emptyUntilAWeekHasEnough() {
        show(SpeedTrend.of(emptyList(), now, zone, null))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_empty_title)))
        assertTrue(hasText(activity.getString(R.string.lab_time_spent_speed_empty)))
        assertTrue(descriptions().none { it.startsWith("Writing,") })
    }

    private var shows = 0

    private fun show(trend: SpeedTrend) {
        // Keyed, so each show is a fresh composition.
        val generation = ++shows
        compose.show { LinguizeTheme(dark = false) { key(generation) { SpeedOverTime(trend) } } }
    }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun hasText(text: String) = texts().any { it == text }

    private fun descriptions(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun nodes(): List<SemanticsNode> {
        fun owner(view: View): SemanticsOwner? {
            val method = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            if (method != null) return method.invoke(view) as SemanticsOwner
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return flatten(requireNotNull(owner(activity.window.decorView)).rootSemanticsNode)
    }
}
