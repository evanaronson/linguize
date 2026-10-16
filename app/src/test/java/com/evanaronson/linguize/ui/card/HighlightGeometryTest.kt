package com.evanaronson.linguize.ui.card

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Android's real text shaping and bidi layout, rather than synthetic character rectangles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightGeometryTest {
    private val measurer = TextMeasurer(
        createFontFamilyResolver(ApplicationProvider.getApplicationContext<Application>()),
        Density(1f),
        LayoutDirection.Ltr,
    )

    @Test
    fun longHebrewAndArabicChangesRemainSelectableAcrossTheirWholeVisualWidth() {
        for (text in listOf("אבגדהוזחטיכלמנסעפצקרשת", "مرحبا بالعالم العربية")) {
            val layout = layout(text)
            val change = highlight(0, text.indices)
            val boxes = lineBoxes(layout, change.range)
            assertTrue(text, boxes.isNotEmpty())
            assertTrue(text, boxes.all { it.right > it.left })
            assertSelectedGlyphsHit(layout, change)
        }
    }

    @Test
    fun wrappedRtlAndExplicitLineBreaksOnlyCoverTheSelectedTextOnEachLine() {
        val text = "אבגדה וזחטי כלמנס\nעפצקר שתאבג דהוזח"
        val layout = layout(text, width = 130)
        val change = highlight(0, 2 until text.length - 2)
        assertTrue(layout.lineCount > 2)
        val boxes = lineBoxes(layout, change.range)
        assertTrue(boxes.map { it.top }.distinct().size > 2)
        assertTrue("Wrapped segments: $boxes", boxes.all { it.right > it.left })
        assertSelectedGlyphsHit(layout, change)
        for (offset in listOf(0, text.lastIndex)) {
            assertNull(nearest(layout, listOf(change), layout.getBoundingBox(offset).center, 0f))
        }
    }

    @Test
    fun mixedBidiRangeLeavesTheUnselectedVisualIslandOutsideDrawingAndHitTargets() {
        val layout = layout("abc אבגדהוז xyz")
        // Logical "bc אב" surrounds the unselected Hebrew letters in visual order.
        val change = highlight(0, 1..5)
        val island = highlight(1, 6..10)
        val boxes = lineBoxes(layout, change.range)
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].right < boxes[1].left)
        val point = layout.getBoundingBox(8).center
        assertFalse(boxes.any { point.x in it.left..it.right && point.y in it.top..it.bottom })
        assertNull(nearest(layout, listOf(change), point, 0f))
        assertEquals(island, nearest(layout, listOf(change, island), point, 24f))
        assertSelectedGlyphsHit(layout, change)
    }

    @Test
    fun adjacentRtlChangesMeetOnOppositeVisualEdgesFromTheirLogicalOrder() {
        val layout = layout("אבגדהוזח")
        val before = highlight(0, 0..3)
        val after = highlight(1, 4..7)
        val beforeBox = lineBoxes(layout, before.range).single()
        val afterBox = lineBoxes(layout, after.range).single()
        assertEquals(afterBox.right, beforeBox.left, 0.01f)
        assertTrue(afterBox.left < beforeBox.left)
        assertEquals(before, nearest(layout, listOf(before, after), layout.getBoundingBox(1).center, 24f))
        assertEquals(after, nearest(layout, listOf(before, after), layout.getBoundingBox(6).center, 24f))
    }

    @Test
    fun ligaturesCombiningMarksAndEmojiClustersHaveTheFullSelectionWidth() {
        for (text in listOf("office", "cafe\u0301", "👍🏽", "👩‍💻", "سلام")) {
            val layout = layout(text)
            val change = highlight(0, text.indices)
            val box = lineBoxes(layout, change.range).single()
            // Selection paths are an independent Compose API and include the shaped cluster's width.
            val selection = layout.getPathForRange(0, text.length).getBounds()
            assertTrue(text, box.right > box.left)
            assertEquals(text, selection.left, box.left, 0.01f)
            assertEquals(text, selection.right, box.right, 0.01f)
            assertSelectedGlyphsHit(layout, change)
        }
    }

    @Test
    fun partialClusterRangesKeepATouchTargetEvenAtZeroAdvanceOffsets() {
        for (text in listOf("e\u0301", "😀", "لا", "👩‍💻")) {
            val layout = layout(text)
            for (offset in text.indices) {
                val change = highlight(offset, offset..offset)
                val boxes = lineBoxes(layout, change.range)
                assertTrue("$text offset $offset", boxes.isNotEmpty())
                assertTrue(boxes.all { it.left <= it.right })
                assertEquals("$text offset $offset", change,
                    nearest(layout, listOf(change), layout.getBoundingBox(offset).center, 0f))
            }
        }
    }

    @Test
    fun lonePunctuationRetainsTheExistingFingertipAllowance() {
        val layout = layout("Hello, world")
        val change = highlight(0, 5..5)
        val box = lineBoxes(layout, change.range).single()
        val near = Offset(box.right + 23f, (box.top + box.bottom) / 2)
        assertEquals(change, nearest(layout, listOf(change), near, 24f))
        assertNull(nearest(layout, listOf(change), near.copy(x = box.right + 25f), 24f))
    }

    private fun layout(text: String, width: Int = 1000): TextLayoutResult = measurer.measure(
        text,
        style = TextStyle(fontSize = 24.sp),
        constraints = Constraints(maxWidth = width),
    )

    private fun highlight(id: Int, range: IntRange) = Highlight(id, range, "old", null)

    private fun assertSelectedGlyphsHit(layout: TextLayoutResult, change: Highlight) {
        for (offset in change.range) {
            val glyph = layout.getBoundingBox(offset)
            if (glyph.width > 0 && !layout.layoutInput.text[offset].isWhitespace()) {
                assertEquals("offset $offset in ${layout.layoutInput.text}", change,
                    nearest(layout, listOf(change), glyph.center, 0f))
            }
        }
    }
}
