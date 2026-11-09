package com.evanaronson.linguize.core.corpus

/**
 * Lab: gaps the writer left in their text (docs/lab/gaps-in-check.md). Every check fills them,
 * as ordinary fixes, so a filled gap is never evidence the writer knew the words. What the
 * learning features read from a text skips them:
 *
 * - a bracketed gap, `[pick me up]`, brackets and all, whatever happened to its fill;
 * - inside a corrected span (an accepted fix, which is what a filled gap is), a common English
 *   word that is not also a Spanish or Catalan word: most likely an unbracketed gap.
 */
object WriterGaps {
    /** Longest phrase in one pair of brackets; longer is more likely a note than a gap. */
    const val MAX_PHRASE = 200

    private val BRACKETED = Regex("""\[[^\[\]\n]{1,$MAX_PHRASE}]""")

    /** The bracketed gaps in [text], brackets included, in order. Empty brackets are not gaps. */
    fun bracketed(text: String): List<Span> = BRACKETED.findAll(text)
        .filter { match -> match.value.any { it.isLetter() } }
        .map { Span(it.range.first, it.range.last + 1) }
        .toList()

    /** Whether `[start, end)` is inside, or overlaps, one of [gaps]. */
    fun inGap(gaps: List<Span>, start: Int, end: Int): Boolean = gaps.any { it.overlaps(start, end) }

    /**
     * Whether [form] (lowercase) is a common English word that is not also an everyday Spanish
     * or Catalan word, so not "me", "he", "has", "on", "come" or "a", nor a loanword people
     * write in Spanish or Catalan ("email", "online", "weekend").
     */
    fun isEnglishOnly(form: String): Boolean = form.trim('\'') in ENGLISH_ONLY

    private val ENGLISH_ONLY = setOf(
        "the", "and", "with", "you", "your", "my", "our", "their", "they", "we", "she", "him", "her", "them",
        "it", "it's", "i'm", "i'll", "i've", "don't", "can't", "won't", "didn't", "isn't", "doesn't",
        "should", "would", "could", "have", "had", "what", "which", "when", "where", "why", "how",
        "this", "that", "these", "those", "there", "here", "from", "about", "into", "off", "up", "out",
        "just", "get", "got", "pick", "drop", "want", "need", "know", "think", "tomorrow", "tonight",
        "today", "yesterday", "please", "thanks", "sorry", "is", "are", "was", "were", "will", "been",
        "of", "for", "to", "at", "bring", "call", "meet", "send", "buy", "find", "keep", "leave",
        "let", "make", "take", "tell", "try", "wait", "help", "back", "around", "something", "anything",
        "someone", "because", "before", "after", "maybe", "already", "still", "soon", "late", "early",
        "borrow", "lend", "fix", "check", "sure", "busy", "running", "catch", "cancel", "reschedule",
        "market", "keys", "key", "room", "work", "house", "friend", "friends",
    )
}
