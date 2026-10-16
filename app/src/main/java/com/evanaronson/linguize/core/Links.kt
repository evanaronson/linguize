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
    /** The most edits that can share a run of text for every combination of them to be tried; more aren't offered. */
    private const val MOST_TOGETHER = 8

    // Conservative starting cap for full-text render/scan work, including expansion.
    // This caps candidate text lengths, not latency; tune only with Android measurements.
    private const val MOST_SCANNED_CHARACTERS = 1_000_000L

    // Failed matches can retry at each character of an unbroken token. Weight scan
    // length by token width as well, so long failed tokens cannot consume the cap
    // through hundreds of scans. Calibrated on the production Kotlin/JVM scanner;
    // Android latency still needs measurement before release.
    private const val MOST_SCAN_WORK = 8_000_000L

    /**
     * [edits] without those that would add a link ("https://…", "www.…", "example.com")
     * to [original] in any combination the writer can accept. Edits that add no link on
     * their own, nor all together, may still add one when only some of them are accepted
     * ("ab 1" → "a!.cd": the "." and "cd" without the "!"), so every combination of the
     * edits that can end up in one run of text without spaces is tried, and if any adds
     * a link, the edits in that combination are withheld. If the complete run introduces
     * an address, discard that proposal together so no partial address is left as a fix.
     * Proposals exceeding the aggregate scan allowance are withheld before rendering.
     */
    fun withoutNew(original: String, edits: List<Edit>): List<Edit> {
        if (edits.isEmpty()) return edits
        val grouped = runs(original, edits)
        var remaining = MOST_SCANNED_CHARACTERS - original.length.toLong()
        if (remaining < 0) return emptyList()
        val originalWidth = longestToken(original).toLong().coerceAtLeast(1)
        var work = MOST_SCAN_WORK - original.length.toLong() * originalWidth
        if (work < 0) return emptyList()
        for (run in grouped) {
            if (run.size > MOST_TOGETHER) continue // These edits are withheld without scanning.
            val longest = original.length.toLong() + run.sumOf {
                (it.replacement.length.toLong() - (it.end - it.start)).coerceAtLeast(0)
            }
            // One complete-run scan plus every nonempty subset, before any pruning.
            val scans = 1L shl run.size
            if (longest > remaining / scans) return emptyList()
            remaining -= longest * scans
            // Removing k spaces can join at most k + 1 original tokens. A rendered
            // token can also contain at most one whitespace-free piece per edit.
            val removedSpaces = run.filter { edit -> edit.replacement.none(Char::isWhitespace) }
                .sumOf { edit -> (edit.start until edit.end).count { original[it].isWhitespace() }.toLong() }
            val widest = originalWidth * (removedSpaces + 1) + run.sumOf { longestToken(it.replacement).toLong() }
            if (longest > work / scans / widest) return emptyList()
            work -= longest * scans * widest
        }
        val known = links(original)
        val dropped = mutableSetOf<Int>()
        for (run in grouped) {
            if (run.size > MOST_TOGETHER || linking(original, known, shown(run))) {
                dropped += run.map { it.id }
                continue
            }
            for (mask in 1 until (1 shl run.size)) {
                val subset = run.filterIndexed { k, _ -> mask shr k and 1 == 1 }
                // A subset containing a withheld edit can no longer be accepted.
                if (subset.none { it.id in dropped } && linking(original, known, shown(subset))) {
                    dropped += subset.map { it.id }
                }
            }
        }
        return edits.filterNot { it.id in dropped }
    }

    private fun longestToken(text: String): Int {
        var longest = 0
        var width = 0
        for (char in text) {
            width = if (char.isWhitespace()) 0 else width + 1
            longest = maxOf(longest, width)
        }
        return longest
    }

    /** Whether [text] holds a link that [original] doesn't. */
    fun hasNewLink(original: String, text: String) = hasNew(links(original), text)

    /** Complete recognized links, not arbitrary substrings: "evil.com" isn't "notevil.com". */
    private fun links(text: String): Set<String> = LINK.findAll(text)
        .map { it.value }
        .filterNot(::isAbbreviation)
        .map(::identity)
        .toSet()

    /** Whether [text] holds a complete link that isn't among the original's [known] links. */
    private fun hasNew(known: Set<String>, text: String) =
        LINK.findAll(text).any { !isAbbreviation(it.value) && identity(it.value) !in known }

    /** Supported abbreviations; the domain match omits their terminal punctuation. */
    private fun isAbbreviation(match: String) =
        match == "EE.UU" || match == "RR.HH"

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
        // A phrase spanning many spaces participates in many stretches, often with exactly
        // the same edits. Their combinations produce the same text; only try each set once.
        return runs.values.distinct()
    }

    /**
     * Compare complete texts: clipping the context could truncate an unchanged adjacent
     * URL and mistake it for a new destination. [MOST_SCANNED_CHARACTERS] bounds the
     * aggregate rendered length across all runs and their combinations.
     */
    private fun linking(original: String, known: Set<String>, edits: List<Edit>) =
        hasNew(known, render(original, edits).text)

    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?"""
    private const val OCTET = """(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"""

    /** What a link's path may end with: not the punctuation of the sentence around it. */
    private const val PATH = """[^\s]*[^\s.,;:!?)\]}"'»…]"""

    /**
     * A web address with a scheme or "www.", a domain name, or a bare ASCII IPv4
     * dotted quad with valid octets and no leading zeros. The quad must be a complete
     * token, not part of a longer dotted version or a word. This isn't every address syntax.
     * DNS names have at most 127 labels. Bounding their repetition also keeps malformed
     * model output with thousands of dotted labels from exhausting the regex stack.
     */
    private val LINK = Regex(
        """(?i)(?:[a-z][a-z0-9+.-]*://|www\.)$PATH""" +
            """|(?:(?<![\p{L}\p{N}\-])$LABEL(?:\.$LABEL){0,125}\.(?:\p{L}{2,24}|xn--[a-z0-9-]+)(?![\p{L}\p{N}\-])""" +
            """|(?<![\p{L}\p{N}_.-])$OCTET(?:\.$OCTET){3}(?![\p{L}\p{N}_-]|\.[\p{L}\p{N}_-]))(?:[/:?#]$PATH)?""",
    )

    private val SCHEME = Regex("""(?i)^[a-z][a-z0-9+.-]*://""")

    /**
     * Explicit addresses keep their path, query, credentials and Unicode host identity.
     * Only the scheme and ASCII host letters are case insensitive. Bare dotted prose
     * retains the accent heuristic, so correcting "estas.Bien" is still allowed.
     */
    private fun identity(link: String): String {
        val authorityStart = SCHEME.find(link)?.value?.length ?: 0
        if (authorityStart == 0 && !link.startsWith("www.", ignoreCase = true) && link.none { it in "/:?#" }) return fold(link)
        val authorityEnd = link.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: link.length
        val hostStart = (link.lastIndexOf('@', authorityEnd - 1) + 1).coerceAtLeast(authorityStart)
        return asciiLower(link.substring(0, authorityStart)) + link.substring(authorityStart, hostStart) +
            asciiLower(link.substring(hostStart, authorityEnd)) + link.substring(authorityEnd)
    }

    private fun asciiLower(text: String) = buildString(text.length) {
        for (char in text) append(if (char in 'A'..'Z') char.lowercaseChar() else char)
    }

    /** Lowercase without accents, only for bare dotted words that may simply need a space. */
    private fun fold(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}
