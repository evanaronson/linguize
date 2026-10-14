package com.evanaronson.linguize.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AlignmentTest {
    private fun ops(original: String, target: String) =
        Alignment.align(original, target).joinToString(" ") { "${it.a?.text ?: "_"}/${it.b?.text ?: "_"}" }

    @Test
    fun repeatedCharactersUseTheDocumentedLastMatchTieBreak() {
        assertEquals("a/_  /_ a/a", ops("a a", "a"))
    }

    @Test
    fun spacesInsideWordsDoNotChangeTheAlignmentOfTheirLetters() {
        val result = Alignment.align("micaabans", "mica abans")
        assertEquals(listOf(null to " "), result.filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
        assertEquals("micaabans", result.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals("mica abans", result.mapNotNull { it.b?.text }.joinToString(""))
    }

    @Test
    fun aUniqueUnchangedWordCannotBeBorrowedForAnAdjacentReplacement() {
        val original = "però de; compte"
        val target = "compte corrent"
        val result = Alignment.align(original, target)
        assertEquals("compte", result.filter { it.isMatch }.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals("però de; ", result.filter { it.b == null }.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals(" corrent", result.filter { it.a == null }.mapNotNull { it.b?.text }.joinToString(""))
        assertEquals(original, result.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals(target, result.mapNotNull { it.b?.text }.joinToString(""))
    }

    @Test
    fun canonicallyEquivalentWordsAreExactAnchors() {
        val result = Alignment.align("però de; cafe\u0301", "café calent")
        assertEquals("cafe\u0301", result.filter { it.isMatch }.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals("café", result.filter { it.isMatch }.mapNotNull { it.b?.text }.joinToString(""))
    }

    @Test
    fun crossedUniqueWordsUseTheCharacterPathInsteadOfGuessingAReordering() {
        assertEquals(fullTable("ab cd", "cd ab"), Alignment.align("ab cd", "cd ab"))
    }

    @Test
    fun aReorderedRunKeepsItsOuterAnchorsAndMarksEveryCrossedWord() {
        val original = "left a b c right"
        val target = "left c b a right"
        val anchors = Alignment.wordAnchors(Alignment.units(original), Alignment.units(target))
        assertEquals(listOf("left", "right"), anchors.ordered.map { it.first.text })
        assertEquals(listOf("a", "b", "c"), anchors.crossed.map { it.first.text })
        val result = Alignment.align(original, target)
        for ((source, destination) in anchors.ordered) {
            val kept = result.filter { it.a != null && it.a.start in source.start until source.end }
            assertTrue(kept.all { it.isMatch })
            assertEquals(source.text, kept.mapNotNull { it.a?.text }.joinToString(""))
            assertEquals(destination.text, kept.mapNotNull { it.b?.text }.joinToString(""))
        }
    }

    @Test
    fun exactWordAnchorsKeepInsertedAndDeletedWordsSeparate() {
        val original = "niños"
        val target = "los niños"
        val insertion = Alignment.align(original, target)
        assertEquals("niños", insertion.filter { it.isMatch }.mapNotNull { it.a?.text }.joinToString(""))
        assertEquals("los ", insertion.filter { it.a == null }.mapNotNull { it.b?.text }.joinToString(""))
        val deletion = Alignment.align(target, original)
        assertEquals("niños", deletion.filter { it.isMatch }.mapNotNull { it.b?.text }.joinToString(""))
        assertEquals("los ", deletion.filter { it.b == null }.mapNotNull { it.a?.text }.joinToString(""))
    }

    @Test
    fun punctuationReplacingAWordSeparatorStaysOneCompleteOperation() {
        for ((original, target) in listOf("el:llibre" to "el llibre", "el llibre" to "el:llibre")) {
            val changes = Alignment.align(original, target).filterNot { it.isMatch }
            assertEquals(listOf(original.substring(2, 3) to target.substring(2, 3)),
                changes.map { it.a?.text to it.b?.text })
        }
    }

    @Test
    fun deletingAWholeWordKeepsItsSpaceSeparateFromNewPunctuation() {
        val changes = Alignment.align("en el sofá y neo está tranquilo", "en el sofá. Neo está tranquilo")
            .filterNot { it.isMatch }.map { it.a?.text to it.b?.text }
        assertEquals(listOf(null to ".", " " to null, "y" to null, "n" to "N"), changes)
        assertEquals(listOf("y" to null, " " to null, null to ","),
            Alignment.align("y hola", ",hola").filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
    }

    @Test
    fun insertingAWholeWordKeepsItsSpaceSeparateFromRemovedPunctuation() {
        val changes = Alignment.align("en el sofá. Neo está tranquilo", "en el sofá y neo está tranquilo")
            .filterNot { it.isMatch }.map { it.a?.text to it.b?.text }
        assertEquals(listOf("." to null, null to " ", null to "y", "N" to "n"), changes)
        assertEquals(listOf(null to "y", null to " ", "," to null),
            Alignment.align(",hola", "y hola").filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
    }

    @Test
    fun aSeparatorBesideAMisspelledWordStillPairsDirectly() {
        val changes = Alignment.align("el:llibbre", "el llibre").filterNot { it.isMatch }
        assertEquals(listOf(":" to " ", "b" to null), changes.map { it.a?.text to it.b?.text })
    }

    @Test
    fun damagedNumericSeparatorsBelongToTheCompleteNumber() {
        for (number in listOf("2,:5", "2.5", "21:30", "1.234,50", "𝟚,:𝟝")) {
            assertEquals(listOf(number), Alignment.units(number).map { it.text })
            assertEquals(Alignment.Type.Word, Alignment.units(number).single().type)
        }
        assertEquals(listOf("2", "!", "5"), Alignment.units("2!5").map { it.text })
        assertEquals(listOf("2", ":", " ", "5"), Alignment.units("2: 5").map { it.text })
        assertEquals(listOf("2.5", "."), Alignment.units("2.5.").map { it.text })
    }

    @Test
    fun lexicalUnitsKeepGraphemesAndAllInputLineSeparatorsComplete() {
        val text = "cafe\u0301\r\n👨‍👩‍👧\u0085𝟚,:𝟝"
        assertEquals(listOf("cafe\u0301", "\r\n", "👨‍👩‍👧", "\u0085", "𝟚,:𝟝"),
            Alignment.units(text).map { it.text })
        assertEquals(listOf(Alignment.Type.Word, Alignment.Type.Space, Alignment.Type.Punct,
            Alignment.Type.Space, Alignment.Type.Word), Alignment.units(text).map { it.type })
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
        assertEquals(listOf("p" to "P", null to "."), ops.filterNot { it.isMatch }.map { it.a?.text to it.b?.text })
    }

    @Test
    fun unanchoredRunsKeepTheExactCheapestPathAndTieBreaking() {
        val random = Random(73)
        // Neither alphabet can combine a missing whole word with a punctuation/space
        // pair, so separator ownership does not normalize the raw character path.
        val alphabets = listOf(
            listOf("a", "A", "á", "b", " ", "  ", "cafe\u0301"),
            listOf(" ", "  ", ",", ".", "!", "😀"),
        )
        for (pieces in alphabets) repeat(250) {
            fun text() = List(random.nextInt(25)) { pieces.random(random) }.joinToString("")
            // Duplicating each passage makes every whole word non-unique, so no
            // lexical anchor constrains the full-table reference's character path.
            val original = text().let { "$it $it" }
            val target = text().let { "$it $it" }
            assertEquals("$original -> $target", fullTable(original, target), Alignment.align(original, target))
        }
    }

    @Test(expected = IllegalArgumentException::class, timeout = 5_000)
    fun aPathologicalRewriteIsRejectedBeforeDoingQuadraticWork() {
        Alignment.align("!".repeat(12_000), "?".repeat(12_000))
    }

    /** Deliberately small, full-table reference: no bands, shared edges or row recomputation. */
    private fun fullTable(original: String, target: String): List<Alignment.Op> {
        // An empty counterpart exposes the tokenizer without any choices of pairing.
        val a = Alignment.align(original, "").mapNotNull { it.a }
        val b = Alignment.align("", target).mapNotNull { it.b }
        fun gap(token: Alignment.Token) = if (token.type == Alignment.Type.Space) 1 else 2
        fun pair(x: Alignment.Token, y: Alignment.Token): Int? = when {
            x.normal == y.normal -> 0
            x.type != Alignment.Type.Word && y.type != Alignment.Type.Word && x.type != y.type -> 2
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
