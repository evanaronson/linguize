package com.evanaronson.linguize.ui.history

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.ui.components.uiLocale
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recent and detail share translated relative labels and the current app locale's date order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rGB")
class HistoryLabelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources
    private val zone = ZoneId.of("UTC")
    private val time = DateTimeFormatter.ofPattern("HH:mm", resources.uiLocale)
    private fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 14, 2, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 10)

    @Test
    fun recentShowsTheTimeForTodayAndYesterdayOnly() {
        assertEquals("Today, 14:02", relativeDate(resources, at(2026, 10, 10), now, time, zone))
        assertEquals("Yesterday, 14:02", relativeDate(resources, at(2026, 10, 9), now, time, zone))
        assertEquals("1 Oct", relativeDate(resources, at(2026, 10, 1), now, time, zone))
        assertEquals("9 Oct 2025", relativeDate(resources, at(2025, 10, 9), now, time, zone))
    }

    @Test
    fun theDetailPageAddsTheTimeToOlderDates() {
        assertEquals("1 Oct, 14:02", relativeDate(resources, at(2026, 10, 1), now, time, zone, alwaysTime = true))
        assertEquals("Today, 14:02", relativeDate(resources, at(2026, 10, 10), now, time, zone, alwaysTime = true))
    }

    @Test
    fun dateOrderFollowsTheCurrentAppConfiguration() {
        val us = context.createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.US) }).resources
        assertEquals("Oct 1", relativeDate(us, at(2026, 10, 1), now, time, zone))
        assertEquals("Oct 9, 2025", relativeDate(us, at(2025, 10, 9), now, time, zone))
    }

    @Test
    fun dateDigitsFollowTheAppLocale() {
        val arabic = context.createConfigurationContext(Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar-EG"))
        }).resources
        val label = relativeDate(arabic, at(2026, 10, 1), now, time, zone)
        assertTrue(label, label.contains('١'))
    }

    @Test
    fun historyLanguageLabelsDistinguishDetectedExplicitAndUnknown() {
        assertEquals("Catalan (auto-detected)", targetLanguageLabel(resources, "ca", null))
        assertEquals("Spanish", targetLanguageLabel(resources, "es", "es"))
        assertEquals("Language unknown", targetLanguageLabel(resources, null, null))
        assertEquals("English", detectedLanguageLabel(resources, "en"))
    }

    @Test
    fun zeroStoredChangesDoNotClaimTheCheckWasComplete() {
        val row = SessionSummary("empty", now, null, null, "Bon dia", null, 0, 0, 0, Verdict.Status.Ok)
        assertEquals("No changes offered", row.statusLine(resources))
        assertEquals("Wrong language", row.copy(status = Verdict.Status.WrongLanguage).statusLine(resources))
    }
}
