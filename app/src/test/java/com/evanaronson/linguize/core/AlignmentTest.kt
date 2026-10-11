package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

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

    @Test(timeout = 10_000)
    fun longTextsAlignWithoutATableForEveryPairOfTokens() {
        // Changes at both ends surround a long unchanged passage. Neither the shared
        // prefix nor suffix shortcut applies; visiting every token pair is prohibitively
        // slow even if the table's rows are compressed.
        val words = List(40_000) { "paraula$it" }
        val original = words.joinToString(" ")
        val target = "P" + original.drop(1) + "."
        val ops = Alignment.align(original, target)
        assertEquals(target, ops.mapNotNull { it.b?.text }.joinToString(""))
        assertEquals(listOf("paraula0" to "Paraula0", null to "."), ops.filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
    }

    @Test
    fun compactAlignmentKeepsTheExactCheapestPathAndTieBreaking() {
        val random = Random(73)
        val pieces = listOf("a", "A", "á", "b", " ", "  ", ",", ".", "!", "😀", "cafe\u0301")
        repeat(500) {
            fun text() = List(random.nextInt(25)) { pieces.random(random) }.joinToString("")
            val original = text()
            val target = text()
            assertEquals("$original -> $target", fullTable(original, target), Alignment.align(original, target))
        }
    }

    /** Deliberately small, full-table reference: no bands, shared edges or row recomputation. */
    private fun fullTable(original: String, target: String): List<Alignment.Op> {
        // An empty counterpart exposes the tokenizer without any choices of pairing.
        val a = Alignment.align(original, "").mapNotNull { it.a }
        val b = Alignment.align("", target).mapNotNull { it.b }
        fun gap(token: Alignment.Token) = if (token.type == Alignment.Type.Space) 1 else 2
        fun pair(x: Alignment.Token, y: Alignment.Token): Int? = when {
            x.normal == y.normal -> 0
            x.type != y.type -> null
            x.type == Alignment.Type.Space || x.type == Alignment.Type.Word && x.folded == y.folded -> 1
            else -> 2
        }
        val costs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) costs[i][0] = costs[i - 1][0] + gap(a[i - 1])
        for (j in 1..b.size) costs[0][j] = costs[0][j - 1] + gap(b[j - 1])
        for (i in 1..a.size) for (j in 1..b.size) {
            costs[i][j] = minOf(costs[i - 1][j] + gap(a[i - 1]), costs[i][j - 1] + gap(b[j - 1]))
            pair(a[i - 1], b[j - 1])?.let { costs[i][j] = minOf(costs[i][j], costs[i - 1][j - 1] + it) }
        }
        val result = ArrayDeque<Alignment.Op>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            val sub = if (i > 0 && j > 0) pair(a[i - 1], b[j - 1]) else null
            when {
                sub != null && costs[i][j] == costs[i - 1][j - 1] + sub -> result.addFirst(Alignment.Op(a[--i], b[--j]))
                i > 0 && costs[i][j] == costs[i - 1][j] + gap(a[i - 1]) -> result.addFirst(Alignment.Op(a[--i], null))
                else -> result.addFirst(Alignment.Op(null, b[--j]))
            }
        }
        return result
    }
}
