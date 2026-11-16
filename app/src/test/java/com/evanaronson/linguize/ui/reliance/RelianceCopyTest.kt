package com.evanaronson.linguize.ui.reliance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.chat.TranslationDwell
import com.evanaronson.linguize.core.chat.TranslationReliance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lab, Translation reliance: the page's words match the rules they describe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RelianceCopyTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun summaryReadsAsTheOwnerAsked() {
        assertEquals("Translation needed for 12 of 40 replies (30%)",
            resources.getQuantityString(R.plurals.lab_reliance_summary_percent, 40, 12, 40, 30))
        assertEquals("Translation needed for 1 of 1 reply", resources.getQuantityString(R.plurals.lab_reliance_summary, 1, 1, 1))
        assertEquals("20% in the last 4 weeks, down from 45% the 4 weeks before", resources.getString(R.string.lab_reliance_trend_down, 20, 45))
    }

    @Test fun howItsCountedMatchesTheRules() {
        assertEquals(2_500L, TranslationDwell.NEEDED_MS)
        assertTrue(resources.getString(R.string.lab_reliance_how_needed).contains("2.5 seconds"))
        val trend = resources.getString(R.string.lab_reliance_how_trend)
        assertTrue(trend.contains("from ${TranslationReliance.MIN_SUMMARY_REPLIES} replies"))
        assertTrue(trend.contains("at least ${TranslationReliance.MIN_TREND_REPLIES} replies"))
        assertTrue(resources.getString(R.string.lab_reliance_weeks_caption).contains("${TranslationReliance.MIN_WEEK_REPLIES} replies"))
    }
}
