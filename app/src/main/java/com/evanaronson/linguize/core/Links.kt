package com.evanaronson.linguize.core

import java.text.Normalizer

/**
 * Keeps suggestions from adding links. The model's texts can be steered by
 * instructions hidden in the text it checks, such as a message someone else
 * wrote that the writer is replying to or trying to understand, and a "fix"
 * that slips in a web address is the most direct way to turn that into harm.
 * So no suggestion may put a link into the text that wasn't already there.
 */
internal object Links {
    /** How far a run of text around an edit is looked at, each way. */
    private const val REACH = 1000

    /** The most edits that can share a run of text for every combination of them to be tried; more aren't offered. */
    private const val MOST_TOGETHER = 8

    /**
     * [edits] without those that would add a link ("https://…", "www.…", "example.com")
     * to [original] in any combination the writer can accept. Edits that add no link on
     * their own, nor all together, may still add one when only some of them are accepted
     * ("ab 1" → "a!.cd": the "." and "cd" without the "!"), so every combination of the
     * edits that can end up in one run of text without spaces is tried, and if any adds
     * a link, none of those edits is offered.
     */
    fun withoutNew(original: String, edits: List<Edit>): List<Edit> {
        val known = fold(original)
        val linking = runs(original, edits).filterNot { run ->
            run.size <= MOST_TOGETHER && (1 until (1 shl run.size)).none { mask ->
                linking(original, known, shown(run.filterIndexed { k, _ -> mask shr k and 1 == 1 }))
            }
        }
        val dropped = linking.flatten().map { it.id }.toSet()
        return edits.filterNot { it.id in dropped }
    }

    /** Whether [text] holds a link that [original] doesn't. */
    fun hasNewLink(original: String, text: String) = hasNew(fold(original), text)

    /** Whether [text] holds a link that isn't in [known], the original text [fold]ed. */
    private fun hasNew(known: String, text: String) =
        LINK.findAll(text).any { !isAbbreviation(it.value) && fold(it.value) !in known }

    /** Short capitals joined by dots, like "EE.UU." or "RR.HH.", are abbreviations, not addresses. */
    private fun isAbbreviation(match: String) =
        match.split('.').all { it.length <= 2 && it.all(Char::isUpperCase) }

    /**
     * [edits] grouped by the stretch of [original] between two spaces that stay spaces
     * whichever edits are accepted: no edit that could take one out (one whose replacement
     * has no space) covers it. Every run of text without spaces, in every combination of
     * edits, lies within one stretch. An edit across such a space belongs to the stretches
     * on both sides of it, the parts of its replacement around its spaces being in them.
     */
    private fun runs(original: String, edits: List<Edit>): Collection<List<Edit>> {
        val bridging = IntArray(original.length + 1)
        for (edit in edits) {
            if (edit.replacement.none(Char::isWhitespace)) {
                bridging[edit.start]++
                bridging[edit.end]--
            }
        }
        // How many spaces that stay spaces come before each position.
        val spacesBefore = IntArray(original.length + 1)
        var covering = 0
        for (k in original.indices) {
            covering += bridging[k]
            spacesBefore[k + 1] = spacesBefore[k] + if (covering == 0 && original[k].isWhitespace()) 1 else 0
        }
        val runs = mutableMapOf<Int, MutableList<Edit>>()
        for (edit in edits) {
            for (run in spacesBefore[edit.start]..spacesBefore[edit.end]) runs.getOrPut(run) { mutableListOf() } += edit
        }
        return runs.values
    }

    /**
     * Whether [edits], applied together to [original], put a new link in a run of text
     * without spaces around any of them. Only as much of the text as those runs can reach
     * is put together.
     */
    private fun linking(original: String, known: String, edits: List<Edit>): Boolean {
        val sorted = edits.sortedWith(editOrder)
        val out = StringBuilder()
        val placed = mutableListOf<IntRange>()
        var pos = maxOf(0, sorted.first().start - REACH)
        for (edit in sorted) {
            if (edit.start < pos) continue
            out.append(original, pos, edit.start)
            val start = out.length
            out.append(edit.replacement)
            placed += start until out.length
            pos = edit.end
        }
        out.append(original, pos, minOf(original.length, pos + REACH))
        val text = out.toString()
        return placed.any { hasNew(known, before(text, it.first) + text.substring(it.first, it.last + 1) + after(text, it.last + 1)) }
    }

    /** The text without spaces that runs up to [at]. */
    private fun before(text: String, at: Int): String {
        var start = at
        while (start > 0 && at - start < REACH && !text[start - 1].isWhitespace()) start--
        return text.substring(start, at)
    }

    /** The text without spaces that runs on from [at]. */
    private fun after(text: String, at: Int): String {
        var end = at
        while (end < text.length && end - at < REACH && !text[end].isWhitespace()) end++
        return text.substring(at, end)
    }

    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?"""

    /** What a link's path may end with: not the punctuation of the sentence around it. */
    private const val PATH = """[^\s]*[^\s.,;:!?)\]}"'»…]"""

    /** A web address with a scheme or "www.", or anything shaped like a domain name. */
    private val LINK = Regex(
        """(?i)(?:[a-z][a-z0-9+.-]*://|www\.)$PATH""" +
            """|(?<![\p{L}\p{N}\-])$LABEL(?:\.$LABEL)*\.(?:\p{L}{2,24}|xn--[a-z0-9-]+)(?![\p{L}\p{N}\-])(?:[/:]$PATH)?""",
    )

    /** Lowercase without accents: a fix to the case or accents of words a dot joins ("estas.Bien") adds no link. */
    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}
