package com.evanaronson.linguize.core.writingpad

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Sentences

/**
 * The Writing pad's live text and what the checks found in it. Typing moves the suggestions
 * with the text and drops the ones it made unsafe; a check's answer is moved from the text it
 * was asked about onto the text as it is when the answer comes. Pure: the caller runs the
 * checks [due] hands out, passes the time, and reports answers to [received] or [failed].
 */
class PadEngine(
    text: String = "",
    idleMillis: Long = CheckSchedule.DEFAULT_IDLE_MILLIS,
    /** Longer texts aren't sent; the checker refuses them too. */
    val maxChars: Int = Int.MAX_VALUE,
) {
    /** What the last answer said about the text as a whole. */
    sealed interface Outcome {
        data object None : Outcome
        data object Reviewed : Outcome
        data object Unclear : Outcome
        data class WrongLanguage(val expected: String, val found: String?) : Outcome
    }

    /** The meaning of [forText], in the writer's own language. */
    data class Meaning(val text: String, val forText: String)

    /** A suggestion the writer dismissed, recognised again in a later answer for the same sentence. */
    private data class Dismissed(val kind: EditKind, val from: String, val replacement: String, val sentence: String)

    val schedule = CheckSchedule(idleMillis) { checkable(it) }

    var text: String = text
        private set
    var suggestions: List<LiveSuggestion> = emptyList()
        private set
    var meaning: Meaning? = null
        private set
    var outcome: Outcome = Outcome.None
        private set

    private val dismissed = mutableSetOf<Dismissed>()
    private var nextKey = 1L

    init {
        schedule.edited(text, Long.MIN_VALUE / 2)
    }

    val tooLong get() = text.length > maxChars

    /** Whether [meaning] describes the text as it is now. */
    val meaningIsCurrent get() = meaning?.forText == text

    /** The writer's text is now [newText]. */
    fun edit(newText: String, now: Long) {
        if (newText == text) return
        suggestions = LiveSuggestions.remap(text, newText, suggestions, sentenceScope = true)
        text = newText
        schedule.edited(newText, now)
    }

    fun due(now: Long): CheckSchedule.Start? = schedule.due(now)

    fun retry(now: Long): CheckSchedule.Start? = schedule.retry(now)

    /** The answer to [request]. False, and nothing changes, when the request is stale. */
    fun received(request: CheckSchedule.Request, result: CheckResult): Boolean {
        if (!schedule.finished(request)) return false
        when (result) {
            is CheckResult.Reviewed -> {
                val found = LiveSuggestions.of(request.text, result) { nextKey++ }
                    .filterNot { sentenceKey(request.text, it) in dismissed }
                suggestions = LiveSuggestions.remap(request.text, text, found, sentenceScope = true)
                meaning = result.meaning.takeIf { it.isNotBlank() }?.let { Meaning(it, request.text) }
                outcome = Outcome.Reviewed
            }
            CheckResult.Unclear -> {
                suggestions = emptyList()
                meaning = null
                outcome = Outcome.Unclear
            }
            is CheckResult.WrongLanguage -> {
                suggestions = emptyList()
                meaning = null
                outcome = Outcome.WrongLanguage(result.expected, result.found)
            }
        }
        return true
    }

    /** [request] failed. False when it is stale. */
    fun failed(request: CheckSchedule.Request): Boolean = schedule.failed(request)

    /**
     * Puts suggestion [key] into the text. Returns where the cursor goes (after the
     * replacement), or null when there is no such suggestion.
     */
    fun accept(key: Long, now: Long): Int? {
        val suggestion = suggestions.firstOrNull { it.key == key } ?: return null
        val before = text
        val after = before.replaceRange(suggestion.start, suggestion.end, suggestion.replacement)
        suggestions = LiveSuggestions.afterAccepting(before, after, suggestion, suggestions - suggestion)
        text = after
        if (meaning?.forText == before) meaning = meaning?.copy(forText = after)
        schedule.accepted(before, after, now)
        return suggestion.start + suggestion.replacement.length
    }

    /** Hides suggestion [key], and keeps it hidden when later checks of its sentence offer it again. */
    fun dismiss(key: Long) {
        val suggestion = suggestions.firstOrNull { it.key == key } ?: return
        dismissed += sentenceKey(text, suggestion)
        suggestions = suggestions - suggestion
    }

    /** The suggestions under a cursor at [offset]: fixes first, then the narrowest. */
    fun at(offset: Int): List<LiveSuggestion> = suggestions.filter { it.covers(offset) }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.end - it.start }, { it.start }))

    fun count(kind: EditKind) = suggestions.count { it.kind == kind }

    /** Stops the running check, as when the editor closes. */
    fun stop() = schedule.stop()

    private fun checkable(text: String) = text.length <= maxChars && text.count { it.isLetter() } >= MIN_LETTERS

    private fun sentenceKey(text: String, suggestion: LiveSuggestion): Dismissed {
        val ranges = Sentences.ranges(text)
        val sentence = (ranges.firstOrNull { suggestion.start in it } ?: ranges.lastOrNull())
            ?.let { text.substring(it.first, maxOf(it.last + 1, suggestion.end)) } ?: text
        return Dismissed(suggestion.kind, suggestion.from, suggestion.replacement, sentence.trim())
    }

    companion object {
        /** Fewer letters than this is not yet a message worth checking. */
        const val MIN_LETTERS = 3
    }
}
