package com.evanaronson.linguize.ui.words

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.corpus.Lemmatizer
import com.evanaronson.linguize.core.corpus.Span
import com.evanaronson.linguize.core.corpus.Utterance
import com.evanaronson.linguize.core.words.WordIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A row says right and wrong apart, with the share right, so neither reads as the other. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WordLabelsTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun words(): com.evanaronson.linguize.core.words.LanguageWords {
        val fixed = "bon dia"
        return WordIndex.build(
            listOf(
                Utterance("check:a", "check", "ca", "bon dia", emptyList(), 1),
                Utterance("check:b", "check", "ca", "bon vespre", emptyList(), 2),
                Utterance("check:c", "check", "ca", fixed, listOf(Span(4, 7)), 3),
            ),
            Lemmatizer.Identity,
        ).language("ca")!!
    }

    @Test
    fun aRowShowsRightWrongAndTheShareRight() {
        val dia = words().word("dia")!!
        // Regression: the row said "Used 2 times", counting the fixed use as a use and hiding it.
        val (status, last) = rowUse(resources, dia) { "Nov 3" }
        assertEquals("1 right, 1 wrong", status)
        assertEquals("50% right, last Nov 3", last)
        assertEquals("3 right, 0 wrong", rowUse(resources, words().word("bon")!!) { "Nov 3" }.first)
        assertEquals("100% right, last Nov 3", rowUse(resources, words().word("bon")!!) { "Nov 3" }.second)
    }

    @Test
    fun aDeckRowNeverReachedHasNoPercent() {
        val (status, last) = rowUse(resources, null) { "Nov 3" }
        assertEquals("Not yet", status)
        assertNull(last)
    }

    @Test
    fun theLabelsSayRightAndWrong() {
        assertEquals("Used wrong", resources.getString(R.string.lab_words_filter_tried))
        assertEquals("Used wrong", resources.getString(R.string.lab_words_detail_tried))
        assertEquals("Used right", resources.getString(R.string.lab_words_detail_right))
        assertEquals("80%", resources.getString(R.string.lab_words_percent, 80))
        assertEquals("Most misused", resources.getString(R.string.lab_words_sort_most_misused))
    }
}
