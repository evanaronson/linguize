package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lab: the gap rules are composed onto the shipped prompt at request time; the shipped assets stay as they are. */
class GapsInCheckTest {
    private val base = Prompt.load { File("src/main/assets/$it").readText() }
    private val gaps = GapsInCheck.load { File("src/main/assets/$it").readText() }

    @Test
    fun theShippedPromptIsUnchangedAndComesFirst() {
        assertEquals(File("src/main/assets/check_prompt.md").readText(), base.system)
        val composed = gaps.system(base)
        assertTrue(composed.startsWith(base.system.trimEnd()))
        assertTrue(composed.trimEnd().endsWith(gaps.addendum.trim()))
        // Loading the gap rules doesn't touch the shipped prompt object.
        assertEquals(File("src/main/assets/check_prompt.md").readText(), base.system)
    }

    @Test
    fun theAddendumIsItsOwnAssetAndCoversBracketsNativeWordsAndMeaning() {
        assertEquals(File("src/main/assets/${GapsInCheck.ASSET}").readText(), gaps.addendum)
        listOf("square brackets", "`Native:`", "`from`", "`why`", "meaning", "naturalize", "wrong_language").forEach {
            assertTrue("missing $it", it in gaps.addendum)
        }
    }

    @Test
    fun theComposedPromptKeepsTheShippedSchemaAndUserMessage() {
        val composed = gaps.prompt(base)
        assertEquals(base.schema, composed.schema)
        assertEquals(gaps.system(base), composed.system)
    }

    @Test
    fun theHashKeepsTheShippedOneAndMarksTheRules() {
        val hash = gaps.hash(base.hash)
        assertTrue(hash.startsWith(base.hash + "-g"))
        assertEquals(hash, gaps.hash(base.hash))
        assertNotEquals(hash, GapsInCheck("other rules").hash(base.hash))
    }
}
