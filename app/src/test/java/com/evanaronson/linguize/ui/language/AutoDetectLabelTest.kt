package com.evanaronson.linguize.ui.language

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.evanaronson.linguize.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Linguize's automatic choice is called "Auto-detect" everywhere: the selector's line under
 * Linguize, the headers under Linguize, the card's picker and its hints. One spelling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AutoDetectLabelTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun theSelectorAndHeadersSayAutoDetect() {
        assertEquals("Auto-detect", resources.getString(R.string.lab_language_scope_all))
        assertEquals("Auto-detect", resources.getString(R.string.lab_ia_progress_all_languages))
    }

    @Test fun theCardPickerAndItsHintsSpellItTheSameWay() {
        assertEquals("Auto-detect", resources.getString(R.string.auto_detect))
        assertEquals("Choose Auto-detect to detect the language.", resources.getString(R.string.choose_auto_language))
        assertEquals(
            "It looks like French. Choose Auto-detect to detect the language.",
            resources.getString(R.string.wrong_language_detected, "French"),
        )
    }

    @Test fun noStringUsesTheOldSpellings() {
        File("src/main/res").walk().filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .forEach { file ->
                val strings = Regex("""<string[^>]*>(.*?)</string>""").findAll(file.readText()).map { it.groupValues[1] }
                strings.forEach { text ->
                    assertFalse("${file.path}: $text", "Auto Detect" in text)
                    assertFalse("${file.path}: $text", "All languages" in text)
                }
            }
    }
}
