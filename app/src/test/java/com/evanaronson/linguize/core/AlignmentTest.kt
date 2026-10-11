package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentTest {
    private fun ops(original: String, target: String) =
        Alignment.align(original, target).joinToString(" ") { "${it.a?.text ?: "_"}/${it.b?.text ?: "_"}" }

    @Test
    fun theWalkBackFromTheEndChoosesWhichOfTwoEqualTokensPairs() {
        // Equal costs either way: the walk back from the end pairs the last "a".
        assertEquals("a/_  /_ a/a", ops("a a", "a"))
        // Shared tokens at the start don't change that choice.
        assertEquals("_/, ,/, bbAb/b", ops(",bbAb", ",,b"))
        assertEquals(" /  A/_ ,/_ A/A ,/_", ops(" A,A,", " A"))
        assertEquals(",/_ ,/, aa/b", ops(",,aa", ",b"))
    }

    @Test
    fun longTextsAlignWithoutATableForEveryPairOfTokens() {
        // 6,000 words changed at both ends, as when a fix capitalizes the first word and adds
        // a full stop: a table for every pair of tokens would take over 500 MB.
        val words = List(6_000) { "paraula$it" }
        val original = words.joinToString(" ")
        val target = "P" + original.drop(1) + "."
        val ops = Alignment.align(original, target)
        assertEquals(target, ops.mapNotNull { it.b?.text }.joinToString(""))
        assertEquals(listOf("paraula0" to "Paraula0", null to "."), ops.filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
    }
}
