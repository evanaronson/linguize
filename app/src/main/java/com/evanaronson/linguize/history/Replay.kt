package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Judgments
import com.evanaronson.linguize.core.Language
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.Settled
import com.evanaronson.linguize.core.Verdict
import com.evanaronson.linguize.core.interpret

/** A past session's card as it ended: the result it showed and the writer's answers it was made with. */
data class Replayed(val result: CheckResult, val settled: List<Settled>)

/**
 * The card [detail] ended with, for reading: the [decided attempt][SessionDetail.decidedAttempt]'s
 * answer, read with [parse], and, when it had suggestions, those that were recorded with
 * what the writer took. Null when no attempt succeeded or its answer can't be read. Pure
 * computation; never throws.
 *
 * What was taken comes from the suggestion rows, not from running today's engine again
 * and matching: the answer is interpreted for what only it holds (meaning, assumptions,
 * how rewordings include fixes), and its edits are used only when they are exactly the
 * recorded ones. When they aren't (the engine has changed since), the card is built from
 * the rows alone, so what was taken is never lost.
 */
fun replay(detail: SessionDetail, parse: (String) -> Verdict): Replayed? {
    val index = detail.decidedAttempt() ?: return null
    val attempt = detail.session.attempts[index]
    val rows = detail.suggestions.filter { it.attempt == index && it.decision != Decision.Superseded }
    // Rows can outlive the engine that made them. Invalid stored spans must never reach
    // rendering, where an out-of-bounds edit would crash the history screen later.
    val original = detail.session.text
    if (rows.any { it.start < 0 || it.end < it.start || it.end > original.length || original.substring(it.start, it.end) != it.fromText }) {
        return null
    }
    val result = try {
        // What that attempt ran with: the card's language and settings may have changed after it.
        val ran = detail.session.reuseKeyOf(index) ?: detail.session.reuseKey
        val judgments = Judgments.tokens.decode(ran.settings.judgments) ?: Judgments.Both
        val requested = ran.requestedLanguage
        val expected = Language.read(requested)?.name ?: requested
        interpret(detail.session.text, parse(attempt.raw ?: return null), judgments, expectedLanguage = expected)
    } catch (_: Exception) {
        return null
    }
    val shown = if (result is CheckResult.Reviewed) {
        // An empty recorded set is evidence too: a later engine must not invent
        // suggestions on a past card or call the discarded proposals a clean check.
        val unrecorded = if (rows.isEmpty()) result.revision.edits.map { it.kind } else emptyList()
        result.copy(revision = recorded(result.revision, rows), withheld = result.withheld + unrecorded)
    } else result
    return Replayed(shown, attempt.settled)
}

/**
 * The revision [rows] record, with the edits that were [Decision.Accepted] accepted:
 * [interpreted]'s edits when they pair off one to one with the rows, else edits made from
 * the rows.
 */
internal fun recorded(interpreted: Revision, rows: List<SuggestionRecord>): Revision {
    val unpaired = interpreted.edits.toMutableList()
    val paired = rows.map { row ->
        val match = unpaired.indexOfFirst {
            it.kind == row.kind && it.start == row.start && it.end == row.end && it.from == row.fromText && it.replacement == row.toText
        }
        if (match < 0) null else row to unpaired.removeAt(match)
    }
    if (unpaired.isEmpty() && paired.all { it != null }) {
        val taken = paired.filterNotNull().filter { (row, _) -> row.decision == Decision.Accepted }.map { (_, edit) -> edit.id }
        // Stored rows are authoritative even when a modern parser would supply different scores.
        val assessments = paired.filterNotNull().associate { (row, edit) -> edit.id to row.assessment }
        val edits = interpreted.edits.map { it.copy(assessment = assessments[it.id]) }
        return Revision(interpreted.original, edits, if (taken.isEmpty()) emptyList() else listOf(taken))
    }
    return fromRows(interpreted.original, rows)
}

/**
 * A revision made from suggestion rows alone. A rewording includes the fixes inside its
 * span or at its edges, which is what the engine means by it for nearly every case.
 */
private fun fromRows(original: String, rows: List<SuggestionRecord>): Revision {
    val plain = rows.mapIndexed { id, row ->
        Edit(id = id, kind = row.kind, start = row.start, end = row.end, from = row.fromText, replacement = row.toText, why = row.why, assessment = row.assessment)
    }
    val edits = plain.map { edit ->
        if (edit.kind != EditKind.Natural) return@map edit
        val inside = plain.filter { it.kind == EditKind.Fix && it.start >= edit.start && it.end <= edit.end }
        edit.copy(includes = inside.map { it.id }.toSet())
    }
    val taken = rows.indices.filter { rows[it].decision == Decision.Accepted }
    return Revision(original, edits, if (taken.isEmpty()) emptyList() else listOf(taken))
}
