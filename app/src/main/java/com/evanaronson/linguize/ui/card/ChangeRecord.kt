package com.evanaronson.linguize.ui.card

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.Assumption
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.Revision
import com.evanaronson.linguize.core.SuggestionAssessment
import com.evanaronson.linguize.core.render
import com.evanaronson.linguize.ui.theme.Linguize

/**
 * One change in the record of a past check: a sent chat message's accepted change, or a
 * suggestion saved in History. Its span is in the text as written.
 */
internal data class RecordedChange(
    val kind: EditKind,
    val start: Int,
    val end: Int,
    val from: String,
    val to: String,
    val why: String?,
    val assessment: SuggestionAssessment? = null,
    val assumptions: List<Assumption> = emptyList(),
    /** What became of it, shown under the change in its explanation ("Accepted"); null for none. */
    val status: String? = null,
)

/** One text of a record and its changes in it; [kind] null when it holds both kinds. */
internal data class RecordPassage(val kind: EditKind?, val text: String, val highlights: List<Highlight>)

/**
 * How a record shows [changes] to [original]: one text with every change in place, as the
 * card writes it ([markChanges]). Changes that overlap (a fix and the rewording that later
 * replaced it) can't share one text, so then it is the card's two texts: the fixes, then the
 * rewordings on the corrected text. Null when a change doesn't fit [original] (its span is
 * outside the text, its old words aren't there, or it overlaps another of its kind), so the
 * caller can fall back; with [dropUnfit], such changes are left out instead.
 */
internal fun recordPassages(original: String, changes: List<RecordedChange>, dropUnfit: Boolean = false): List<RecordPassage>? {
    fun RecordedChange.fits() = start in 0..end && end <= original.length && original.substring(start, end) == from &&
        (from.isNotEmpty() || to.isNotEmpty())
    fun RecordedChange.edit(id: Int) = Edit(id, kind, start, end, from, to, why)
    if (dropUnfit) {
        // Keep, in reading order, what fits and doesn't overlap a kept change of its kind.
        val kept = mutableListOf<RecordedChange>()
        for (change in changes.filter { it.fits() }.sortedWith(compareBy({ it.start }, { it.end > it.start }, { it.end }))) {
            if (kept.none { it.kind == change.kind && it.edit(0).overlaps(change.edit(1)) }) kept += change
        }
        return recordPassages(original, kept)
    }
    if (changes.isEmpty()) return emptyList()
    if (!changes.all { it.fits() }) return null
    val edits = changes.mapIndexed { index, change -> change.edit(index) }
    fun highlights(of: List<Edit>, ranges: Map<Int, IntRange>): List<Highlight>? = of.map { edit ->
        val change = changes[edit.id]
        Highlight(edit.id, ranges[edit.id] ?: return null, change.from, change.why, change.assessment,
            assumptions = change.assumptions, kind = change.kind, status = change.status)
    }
    val overlapping = edits.any { a -> edits.any { b -> a.id != b.id && a.overlaps(b) } }
    if (!overlapping) {
        val rendered = render(original, edits)
        return listOf(RecordPassage(null, rendered.text, highlights(edits, rendered.ranges) ?: return null))
    }
    val revision = Revision(original, edits)
    return listOf(EditKind.Fix, EditKind.Natural).mapNotNull { kind ->
        val own = edits.filter { it.kind == kind }
        if (own.isEmpty()) return@mapNotNull null
        val preview = revision.previewOnCorrected(kind)
        RecordPassage(kind, preview.text, highlights(own, preview.ranges) ?: return null)
    }
}

/**
 * The record of a past check, read-only: the card's own change view ([HighlightedText]) with
 * nothing to accept or copy. A tap on a change opens its explanation under the text, the same
 * panel the card uses (the change, its reason, Teach me, the Phrasebook star), one at a time;
 * there is no list of every change. Used for a sent chat message and a saved check in History.
 * [spoken] is what screen readers hear for a single text; null is the text itself.
 */
@Composable
internal fun ChangeRecord(passages: List<RecordPassage>, modifier: Modifier = Modifier, spoken: String? = null) {
    var open by rememberSaveable(passages.map { it.text }) { mutableStateOf<Int?>(null) }
    val selection = ReviewSelection(open, { open = it }, onSkip = null, position = null)
    Column(modifier) {
        passages.forEachIndexed { index, passage ->
            val fix = passage.kind == EditKind.Fix
            val mark = if (passage.kind == EditKind.Natural) Linguize.colors.rewording else Linguize.colors.proof
            if (passages.size > 1) {
                // Two texts, as on the card: each says what it holds.
                if (index > 0) Spacer(Modifier.height(14.dp))
                val count = passage.highlights.size
                Text(
                    pluralStringResource(if (fix) R.plurals.fix_count else R.plurals.rewording_count, count, count),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (fix) mark else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            HighlightedText(
                passage.text, passage.highlights, mark,
                onReplace = null, onCopy = null, copyDescription = null,
                dotted = passage.kind == EditKind.Natural,
                edge = Linguize.colors.proof,
                selection = selection,
                spoken = spoken?.takeIf { passages.size == 1 },
            )
        }
    }
}
