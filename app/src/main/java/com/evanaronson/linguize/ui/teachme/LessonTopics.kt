package com.evanaronson.linguize.ui.teachme

import androidx.compose.runtime.staticCompositionLocalOf
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.teachme.LessonTopic
import com.evanaronson.linguize.history.SuggestionRecord

/**
 * What a screen knows about the text its explanations come from: the language it was
 * checked in (a code or English name, as History stores it) and the text itself. The live
 * card doesn't provide one; its lessons get the sentence from the card and let the model
 * name the language.
 */
data class LessonSource(val language: String?, val text: String?)

val LocalLessonSource = staticCompositionLocalOf<LessonSource?> { null }

/** The English name for a stored language (code or name), or null when unknown. */
internal fun lessonLanguage(stored: String?): String? =
    stored?.takeIf { it.isNotBlank() }?.let { Language.read(it)?.name ?: it }

/**
 * The sentence of [text] around [start] until [end] (exclusive), trimmed, at most [max]
 * characters; null when the range doesn't fit the text. Sentences end at . ! ? … or a line
 * break.
 */
internal fun sentenceAround(text: String, start: Int, end: Int, max: Int = 400): String? {
    if (start < 0 || end < start || end > text.length) return null
    val enders = ".!?…\n\r"
    var from = start
    while (from > 0 && text[from - 1] !in enders) from--
    var to = end
    while (to < text.length && text[to] !in enders) to++
    if (to < text.length && text[to] !in "\n\r") to++
    val sentence = text.substring(from, to).trim()
    if (sentence.isEmpty()) return null
    if (sentence.length <= max) return sentence
    // Too long to be one sentence: keep a window around the change instead.
    val center = (start + end) / 2
    val windowStart = (center - max / 2).coerceIn(0, maxOf(0, text.length - max))
    return text.substring(windowStart, minOf(text.length, windowStart + max)).trim()
}

/** A topic for one change, or null when there is nothing to teach. */
internal fun lessonTopic(from: String, to: String, why: String?, language: String?, sentence: String?): LessonTopic? =
    why?.let { LessonTopic(from.trim(), to.trim(), it.trim(), lessonLanguage(language), sentence) }
        ?.takeIf { it.teachable }

/**
 * A saved suggestion's topic. Its sentence comes from the saved text only when the saved
 * span still holds the saved words; historical spans are never trusted on their own.
 */
internal fun savedLessonTopic(row: SuggestionRecord, source: LessonSource?): LessonTopic? {
    val text = source?.text
    val sentence = text?.takeIf { row.start in 0..row.end && row.end <= it.length && it.substring(row.start, row.end) == row.fromText }
        ?.let { sentenceAround(it, row.start, row.end) }
    return lessonTopic(row.fromText, row.toText, row.why, source?.language, sentence)
}
