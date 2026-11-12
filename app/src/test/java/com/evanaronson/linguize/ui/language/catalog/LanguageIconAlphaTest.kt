package com.evanaronson.linguize.ui.language.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language icon is drawn at one opacity, [LanguageIconAlpha], in one file: the catalog's
 * flags and emblems, the speech bubble and Linguize's Auto mark. The outline stays opaque, and
 * no call site fades an icon of its own.
 */
class LanguageIconAlphaTest {
    private val source = File("src/main/java/com/evanaronson/linguize")
    private val icon = File(source, "ui/language/catalog/LanguageIcon.kt").readText()

    @Test fun theAlphaIsEightyPercent() {
        assertEquals(0.8f, LanguageIconAlpha, 0f)
    }

    @Test fun flagsEmblemsTheBubbleAndAutoAllUseIt() {
        // Picture (flags and emblems): the painter fades, the border modifier doesn't.
        val picture = icon.substringAfter("private fun Picture(").substringBefore("\n}")
        assertTrue(picture, Regex("""Image\(\s*painterResource\(drawable\), contentDescription = null,\s*alpha = LanguageIconAlpha,""").containsMatchIn(picture))
        // The speech bubble: its disc and its glyph; and Linguize's "A".
        assertTrue("surfaceVariant.copy(alpha = LanguageIconAlpha)" in icon)
        assertEquals(2, Regex("""onSurfaceVariant\.copy\(alpha = LanguageIconAlpha\)""").findAll(icon).count())
        // Linguize's "A" is drawn here too, not in the selector.
        assertTrue("fun AutoLanguageIcon" in icon)
        assertTrue("R.drawable.lab_lang_selector_auto" in icon)
    }

    @Test fun theOutlineStaysOpaque() {
        assertFalse(Regex("""outline(Variant)?\.copy\(alpha""").containsMatchIn(icon))
        assertFalse(Regex("""Modifier\.alpha|\.alpha\(""").containsMatchIn(icon))
    }

    @Test fun noCallSiteDrawsOrFadesAnIconItself() {
        val others = source.walk().filter { it.isFile && it.extension == "kt" && it.name != "LanguageIcon.kt" }
        others.forEach { file ->
            val text = file.readText()
            assertFalse(file.path, "lab_lang_selector_auto" in text)
            text.lines().filter { "LanguageIcon(" in it }.forEach { line ->
                assertFalse("${file.path}: $line", "alpha" in line)
            }
        }
    }
}
