package com.evanaronson.linguize.history.practice

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Sentences
import com.evanaronson.linguize.core.practice.Answers
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.history.Decision
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.HistoryEvidenceReader
import com.evanaronson.linguize.history.HistoryEvidenceResult
import com.evanaronson.linguize.history.HistoryEvidenceWindow
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SuggestionRecord

/**
 * Practice items derived from History, never stored with it. An item is one fix the writer
 * applied ([Decision.Accepted], [EditKind.Fix]): not one they undid or left on offer, and
 * not a rewording, which corrects no mistake. The same fix made more than once becomes one
 * item. Deleting the check removes it from the next build, and with it its scheduling state.
 */
object PracticeItems {
    /** Longest sentence shown whole; longer ones are cut to the words around the fix. */
    const val MAX_SENTENCE = 240
    private const val CONTEXT = 90

    /**
     * Every item in [details], newest check first. Repeats of the same fix (same words,
     * same replacement, same language) keep one representative: one in [preferred] (it has
     * scheduling state) if any, otherwise the oldest, so the id stays put as checks are added.
     */
    fun from(details: List<SessionDetail>, preferred: Set<String> = emptySet()): List<PracticeItem> =
        details.flatMap(::itemsOf)
            .groupBy(::repeatKey)
            .values
            .map { same ->
                same.minWith(compareBy({ it.id !in preferred }, { it.checkedAt }, { it.id }))
            }
            .sortedWith(compareByDescending<PracticeItem> { it.checkedAt }.thenBy { it.id })

    fun repeatKey(item: PracticeItem): String =
        listOf(item.language.orEmpty(), Answers.normalize(item.wrong).lowercase(), Answers.normalize(item.right).lowercase())
            .joinToString("\u0000")

    /** The items one saved check gives, before repeats across checks are merged. */
    fun itemsOf(detail: SessionDetail): List<PracticeItem> {
        val text = detail.session.text
        val applied = detail.suggestions.filter { it.decision == Decision.Accepted && it.spans(text) }
        if (applied.isEmpty()) return emptyList()
        val sentences = Sentences.ranges(text)
        return applied.filter { it.kind == EditKind.Fix && it.fromText != it.toText }.mapNotNull { fix ->
            val context = applied.filter { it !== fix && it.attempt == fix.attempt }
            item(detail, fix, context, sentences)
        }
    }

    private fun SuggestionRecord.spans(text: String) =
        start in 0..end && end <= text.length && text.substring(start, end) == fromText

    private fun item(
        detail: SessionDetail,
        fix: SuggestionRecord,
        others: List<SuggestionRecord>,
        sentences: List<IntRange>,
    ): PracticeItem? {
        val text = detail.session.text
        val lastChar = if (fix.end > fix.start) fix.end - 1 else fix.start
        var from = sentences.lastOrNull { it.first <= fix.start }?.first ?: 0
        var to = sentences.lastOrNull { it.first <= lastChar }?.let { it.last + 1 } ?: text.length
        to = maxOf(to, fix.end)
        if (to - from > MAX_SENTENCE) {
            from = wordStart(text, maxOf(from, fix.start - CONTEXT))
            to = wordEnd(text, minOf(to, fix.end + CONTEXT))
        }
        while (from < fix.start && text[from].isWhitespace()) from++
        while (to > fix.end && text[to - 1].isWhitespace()) to--

        // The writer's other applied fixes in this sentence are already made, so only this mistake differs.
        val context = mutableListOf<SuggestionRecord>()
        others.filter { it.start >= from && it.end <= to && !overlaps(it, fix) }
            .sortedBy { it.start }
            .forEach { edit -> if (context.none { overlaps(it, edit) }) context += edit }

        val built = StringBuilder()
        var at = from
        var wrongStart = 0
        var wrongEnd = 0
        for (edit in (context + fix).sortedWith(compareBy({ it.start }, { it.end }))) {
            built.append(text, at, edit.start)
            if (edit === fix) {
                wrongStart = built.length
                built.append(edit.fromText)
                wrongEnd = built.length
            } else built.append(edit.toText)
            at = edit.end
        }
        built.append(text, at, to)
        val sentence = built.toString()
        val (start, end) = widen(sentence, wrongStart, wrongEnd, fix.toText)
        val right = sentence.substring(start, wrongStart) + fix.toText + sentence.substring(wrongEnd, end)
        val wrong = sentence.substring(start, end)
        if (wrong.isBlank() || Answers.normalize(wrong) == Answers.normalize(right)) return null
        val session = detail.session
        return PracticeItem(
            id = fix.id,
            sessionId = session.id,
            checkedAt = session.startedAt,
            language = session.attempts.getOrNull(fix.attempt)?.targetLanguage ?: session.targetLanguage,
            sentence = sentence,
            wrongStart = start,
            wrongEnd = end,
            right = right,
            why = fix.why?.takeIf { it.isNotBlank() },
            assessment = fix.assessment,
            origin = session.origin?.token,
        )
    }

    private fun overlaps(a: SuggestionRecord, b: SuggestionRecord): Boolean =
        a.start < b.end && b.start < a.end || (a.start == a.end && a.start == b.start) || (b.start == b.end && b.start == a.start)

    /**
     * Widens a change that cuts into a word to the whole word, so the writer types words,
     * not fragments. A bare insertion takes the next word (or the previous one at the end).
     */
    internal fun widen(sentence: String, start: Int, end: Int, replacement: String): Pair<Int, Int> {
        var a = start
        var b = end
        val firstIn = sentence.getOrNull(start)?.takeIf { start < end }
        val lastIn = sentence.getOrNull(end - 1)?.takeIf { start < end }
        if (a > 0 && isWord(sentence[a - 1]) && (firstIn.isWord() || replacement.firstOrNull().isWord())) {
            while (a > 0 && isWord(sentence[a - 1])) a--
        }
        if (b < sentence.length && isWord(sentence[b]) && (lastIn.isWord() || replacement.lastOrNull().isWord())) {
            while (b < sentence.length && isWord(sentence[b])) b++
        }
        if (a == b) {
            var next = b
            while (next < sentence.length && sentence[next].isWhitespace()) next++
            var after = next
            while (after < sentence.length && isWord(sentence[after])) after++
            if (after > next) {
                b = after
            } else {
                var before = a
                while (before > 0 && sentence[before - 1].isWhitespace()) before--
                var word = before
                while (word > 0 && isWord(sentence[word - 1])) word--
                a = word
            }
        }
        return a to b
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || c == '·'
    private fun Char?.isWord() = this != null && isWord(this)

    private fun wordStart(text: String, at: Int): Int {
        var i = at
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    private fun wordEnd(text: String, at: Int): Int {
        var i = at
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }
}

/** A successful bounded deck read, or a retryable failure that must not prune schedules. */
sealed interface PracticeEvidenceResult {
    data class Ready(
        val items: List<PracticeItem>,
        val window: HistoryEvidenceWindow,
        val revision: Long? = null,
    ) : PracticeEvidenceResult
    data object Failed : PracticeEvidenceResult
}

/** Rebuilds Practice's items from shared History evidence, retaining its own selection policy. */
class PracticeSource(private val evidence: HistoryEvidenceReader) {
    constructor(history: HistoryStore) : this(HistoryEvidenceReader(history))

    /**
     * The newest [maxChecks] checks, rounded to a full 50-row page as this source has always
     * read them. Production uses exactly 500. Failure cannot be mistaken for a smaller deck.
     */
    suspend fun read(preferred: Set<String> = emptySet(), maxChecks: Int = MAX_CHECKS): PracticeEvidenceResult {
        val window = (((maxChecks.toLong().coerceAtLeast(1) + PAGE - 1) / PAGE) * PAGE)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (val result = evidence.read(window)) {
            is HistoryEvidenceResult.Ready -> PracticeEvidenceResult.Ready(
                PracticeItems.from(result.snapshot.details, preferred), result.snapshot.window, result.snapshot.revision,
            )
            HistoryEvidenceResult.Failed -> PracticeEvidenceResult.Failed
        }
    }

    /** Convenience for callers that need items only; null is a failed read, never an empty deck. */
    suspend fun load(preferred: Set<String> = emptySet(), maxChecks: Int = MAX_CHECKS): List<PracticeItem>? =
        when (val result = read(preferred, maxChecks)) {
            is PracticeEvidenceResult.Ready -> result.items
            PracticeEvidenceResult.Failed -> null
        }

    companion object {
        const val MAX_CHECKS = 500
        private const val PAGE = 50
    }
}
