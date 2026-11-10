package com.evanaronson.linguize.ui.practice

import com.evanaronson.linguize.core.language.LanguageScope
import com.evanaronson.linguize.core.practice.AnswerResult
import com.evanaronson.linguize.core.practice.Answers
import com.evanaronson.linguize.core.practice.Deck
import com.evanaronson.linguize.core.practice.Grade
import com.evanaronson.linguize.core.practice.Hint
import com.evanaronson.linguize.core.practice.PracticeItem
import com.evanaronson.linguize.core.practice.PracticeProgress
import com.evanaronson.linguize.core.practice.ReviewState
import com.evanaronson.linguize.core.practice.Schedule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A saved command has no text. Its expected schedule makes a restored retry conditional. */
@Serializable
internal data class PracticeGrade(
    val id: String,
    val grade: String,
    val now: Long,
    val today: Long,
    val before: ReviewState?,
) {
    val value: Grade get() = Grade.valueOf(grade)
}

/** Only identifiers, numeric schedules and small user decisions enter saved instance state. */
@Serializable
internal data class PracticeCheckpoint(
    val version: Int = 1,
    val entry: String? = null,
    val run: SavedPracticeRun? = null,
    val pending: PracticeGrade? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun read(text: String?): PracticeCheckpoint? = text?.let {
            try {
                json.decodeFromString<PracticeCheckpoint>(it).takeIf { saved ->
                    saved.version == 1 && saved.run?.valid() != false &&
                        (saved.pending == null || saved.run?.let { run ->
                            saved.pending.grade in Grade.entries.map(Grade::name) && run.answered != null &&
                                run.queue.getOrNull(run.index) == saved.pending.id && run.grades[saved.pending.id] == saved.pending.grade
                        } == true)
                }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

@Serializable
internal data class SavedPracticeRun(
    val id: String,
    val language: String?,
    val queue: List<String>,
    val index: Int,
    val answered: SavedPracticeAnswer?,
    val grades: Map<String, String>,
    val repeated: Set<String>,
    val baseline: Map<String, ReviewState?>,
    val correctFirst: Boolean?,
) {
    fun valid(): Boolean {
        val occurrences = queue.groupingBy { it }.eachCount()
        return id.isNotBlank() && index in 0..queue.size && queue.all { it in baseline } &&
            grades.keys.all { it in occurrences } && grades.values.all { it in Grade.entries.map(Grade::name) } &&
            occurrences.values.all { it in 1..2 } && repeated == occurrences.filterValues { it == 2 }.keys &&
            repeated.all { grades[it] == Grade.Again.name } &&
            (answered == null || (index < queue.size && queue[index] in grades))
    }

    /**
     * Disk owns scheduling. A stale unanswered checkpoint cannot claim a review made after
     * it was saved: another live run may own it. Omit that item, rather than replay or score it.
     * Known answers survive an acknowledged matching write; unfinished saved commands retry
     * only against their captured prior schedule. Discontinuous newer schedules supersede them.
     */
    fun restore(items: List<PracticeItem>, progress: PracticeProgress, pending: PracticeGrade?): RestoredPractice {
        val available = items.filter { LanguageScope.keeps(it.language, language) }.associateBy { it.id }
        val keep = queue.toSet().filterTo(HashSet()) { itemId ->
            if (itemId !in available) return@filterTo false
            val before = baseline[itemId]
            val actual = progress.states[itemId]
            val grade = grades[itemId]?.let(Grade::valueOf)
            when {
                pending?.id == itemId -> actual == pending.before ||
                    actual == Schedule.review(pending.before, pending.value, pending.now)
                grade == null -> actual == before
                actual == null -> false
                else -> actual == Schedule.review(before, grade, actual.reviewedAt)
            }
        }
        val surviving = queue.mapIndexedNotNull { position, itemId ->
            available[itemId]?.takeIf { itemId in keep }?.let { position to it }
        }
        val position = surviving.count { it.first < index }
        val current = surviving.getOrNull(position)?.second
        val sameCurrent = surviving.getOrNull(position)?.first == index
        val answer = if (sameCurrent && current != null) answered?.read(current) else null
        val restored = PracticeRun(
            queue = surviving.map { it.second }, index = position, answered = answer,
            grades = grades.filterKeys { it in keep }.mapValues { Grade.valueOf(it.value) },
            repeated = repeated.intersect(keep), id = id, language = language,
            baseline = baseline.filterKeys { it in keep },
            correctFirst = if (sameCurrent) correctFirst else current?.let { Deck.correctFirst(it, progress) },
        )
        val unfinished = pending?.takeIf {
            it.id == current?.id && answer != null && progress.states[it.id] == it.before
        }
        return RestoredPractice(restored, unfinished)
    }

    companion object {
        fun of(run: PracticeRun) = SavedPracticeRun(
            run.id, run.language, run.queue.map { it.id }, run.index,
            run.answered?.let { answer -> run.current?.let { SavedPracticeAnswer.of(answer, it) } },
            run.grades.mapValues { it.value.name }, run.repeated, run.baseline, run.correctFirst,
        )
    }
}

internal data class RestoredPractice(val run: PracticeRun?, val pending: PracticeGrade?)

/** Accent feedback stores a word's position in the rebuilt fix, never the word itself. */
@Serializable
internal data class SavedPracticeAnswer(
    val result: String,
    val word: Int? = null,
    val revealed: Boolean = false,
    val pickedCorrect: Boolean? = null,
) {
    fun read(item: PracticeItem): Answered? {
        val expected = word?.let { Answers.words(item.right).getOrNull(it) }
        val answer = when (result) {
            "correct" -> AnswerResult.Correct
            "wrong" -> AnswerResult.Wrong
            "unchanged" -> AnswerResult.Unchanged
            "missing_accent" -> AnswerResult.Almost(expected?.let { Hint.MissingAccent(it) } ?: Hint.Accents)
            "extra_accent" -> AnswerResult.Almost(expected?.let { Hint.ExtraAccent(it) } ?: Hint.Accents)
            "wrong_accent" -> AnswerResult.Almost(expected?.let { Hint.WrongAccent(it) } ?: Hint.Accents)
            "accents" -> AnswerResult.Almost(Hint.Accents)
            "punctuation" -> AnswerResult.Almost(Hint.Punctuation)
            "capitals" -> AnswerResult.Almost(Hint.Capitals)
            "spelling" -> AnswerResult.Almost(Hint.Spelling)
            else -> return null
        }
        return Answered(answer, revealed, pickedCorrect)
    }

    companion object {
        fun of(answer: Answered, item: PracticeItem): SavedPracticeAnswer {
            val hint = (answer.result as? AnswerResult.Almost)?.hint
            val word = when (hint) {
                is Hint.MissingAccent -> hint.word
                is Hint.ExtraAccent -> hint.word
                is Hint.WrongAccent -> hint.word
                else -> null
            }
            val result = when (val value = answer.result) {
                AnswerResult.Correct -> "correct"
                AnswerResult.Wrong -> "wrong"
                AnswerResult.Unchanged -> "unchanged"
                is AnswerResult.Almost -> when (value.hint) {
                    is Hint.MissingAccent -> "missing_accent"
                    is Hint.ExtraAccent -> "extra_accent"
                    is Hint.WrongAccent -> "wrong_accent"
                    Hint.Accents -> "accents"
                    Hint.Punctuation -> "punctuation"
                    Hint.Capitals -> "capitals"
                    Hint.Spelling -> "spelling"
                }
            }
            return SavedPracticeAnswer(result, word?.let { Answers.words(item.right).indexOf(it) }, answer.revealed, answer.pickedCorrect)
        }
    }
}
