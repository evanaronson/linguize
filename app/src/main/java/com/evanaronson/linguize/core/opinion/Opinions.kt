package com.evanaronson.linguize.core.opinion

import com.evanaronson.linguize.core.Alignment
import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.core.Edit
import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.editOrder
import com.evanaronson.linguize.core.render

/** How two answers to the same check relate at one place in the text. */
enum class Agreement {
    /** Both answers make the same change there. */
    Agree,

    /** Only the first answer changes it; the second leaves it as written. */
    OnlyFirst,

    /** Only the second answer changes it. */
    OnlySecond,

    /** Both change it, differently. */
    Disagree,
}

/**
 * One place in the original text where at least one answer suggests a change. [start] and
 * [end] cover every change involved there, in the original text; [from] is that original
 * text, and [first] and [second] what each answer makes of it (null when that answer leaves
 * it as written). [firstIds] and [secondIds] are the edit ids within each answer.
 */
data class OpinionItem(
    val agreement: Agreement,
    val start: Int,
    val end: Int,
    val from: String,
    val first: String?,
    val second: String?,
    val firstIds: List<Int>,
    val secondIds: List<Int>,
    /** The section the first answer puts this change in; null when it has none here. */
    val firstKind: EditKind?,
    val secondKind: EditKind?,
) {
    /**
     * The same three texts without the whole words and spaces they all share at either end,
     * for showing the change in a few words ("cami becomes camí", not the whole phrase).
     */
    val shown: ShownChange
        get() = Opinions.shared(from, first, second)
}

/** [OpinionItem] texts with shared context trimmed; null where that answer made no change. */
data class ShownChange(val from: String, val first: String?, val second: String?)

/** Two answers to one check, compared change by change. */
data class OpinionComparison(
    /** In text order. */
    val items: List<OpinionItem>,
    /** The first answer found nothing to change (and offered everything it found). */
    val firstLooksGood: Boolean,
    val secondLooksGood: Boolean,
    /** Either answer had changes that couldn't be offered safely; those aren't compared. */
    val incomplete: Boolean,
) {
    /** Both answers found the text fine as written. */
    val bothLookGood: Boolean get() = firstLooksGood && secondLooksGood

    /** Every change was suggested the same way by both. */
    val allAgree: Boolean get() = items.isNotEmpty() && items.all { it.agreement == Agreement.Agree }

    fun count(agreement: Agreement): Int = items.count { it.agreement == agreement }

    /** What the second answer says about the first answer's edit [id]; null when it isn't one of them. */
    fun forFirstEdit(id: Int): OpinionItem? = items.firstOrNull { id in it.firstIds }
}

/** What a second answer to a reviewed check amounts to, next to the first. */
sealed interface SecondAnswer {
    data class Compared(val comparison: OpinionComparison) : SecondAnswer

    /** The second model couldn't tell what the text means. */
    data object Unclear : SecondAnswer

    /** The second model took the text for another language ([found], when it said). */
    data class WrongLanguage(val found: String?) : SecondAnswer
}

/**
 * Compares two answers to the same check: the same text, prompt and settings, asked of two
 * models. Both answers are already anchored to the writer's original text when they are
 * interpreted, so their changes are matched by span and replacement, without asking a model again:
 *
 * 1. Within a section (fixes, rewordings), changes with the same span and replacement agree.
 * 2. So do changes whose spans touch and that turn that stretch of text into the same thing,
 *    though one included a neighbouring word or space ("cami" or "el cami" becoming "camí").
 * 3. What's left is grouped where spans overlap or meet. A group with changes from only one
 *    answer is one item per change; one with changes from both agrees when the group's text
 *    comes out the same and disagrees otherwise.
 * 4. A change one answer calls a fix and the other a rewording agrees when it has the same
 *    effect on the text.
 */
object Opinions {
    /** [second] as it relates to [first]; both must be answers about the same original text. */
    fun compare(first: CheckResult.Reviewed, second: CheckResult): SecondAnswer = when (second) {
        is CheckResult.Reviewed -> SecondAnswer.Compared(compare(first, second))
        CheckResult.Unclear -> SecondAnswer.Unclear
        is CheckResult.WrongLanguage -> SecondAnswer.WrongLanguage(second.found)
    }

    fun compare(first: CheckResult.Reviewed, second: CheckResult.Reviewed): OpinionComparison {
        val original = first.revision.original
        require(original == second.revision.original) { "Only answers about the same text can be compared" }
        val kinds = EditKind.entries.filter { it in first.kinds || it in second.kinds }
        val groups = kinds.flatMap { kind -> match(original, first.revision.edits(kind), second.revision.edits(kind)) }
        val items = acrossKinds(original, groups).map { it.item(original) }
            .sortedWith(compareBy<OpinionItem>({ it.start }, { it.end }, { it.agreement.ordinal }))
        return OpinionComparison(
            items,
            firstLooksGood = first.looksGood,
            secondLooksGood = second.looksGood,
            incomplete = first.withheld.isNotEmpty() || second.withheld.isNotEmpty(),
        )
    }

    /** Changes from either answer that belong together; one of the lists may be empty. */
    private class Group(val first: List<Edit>, val second: List<Edit>) {
        val all get() = first + second
        val start get() = all.minOf { it.start }
        val end get() = all.maxOf { it.end }

        fun item(original: String): OpinionItem {
            val start = start
            val end = end
            val firstText = first.takeIf { it.isNotEmpty() }?.let { textOf(original, it, start, end) }
            val secondText = second.takeIf { it.isNotEmpty() }?.let { textOf(original, it, start, end) }
            val agreement = when {
                firstText == null -> Agreement.OnlySecond
                secondText == null -> Agreement.OnlyFirst
                Alignment.same(firstText, secondText) -> Agreement.Agree
                else -> Agreement.Disagree
            }
            return OpinionItem(
                agreement, start, end, original.substring(start, end), firstText, secondText,
                first.sortedWith(editOrder).map { it.id }, second.sortedWith(editOrder).map { it.id },
                first.firstOrNull()?.kind, second.firstOrNull()?.kind,
            )
        }
    }

    /** One section's changes from each answer, grouped as steps 1 to 3 describe. */
    private fun match(original: String, first: List<Edit>, second: List<Edit>): List<Group> {
        val groups = mutableListOf<Group>()
        val restFirst = first.sortedWith(editOrder).toMutableList()
        val restSecond = second.sortedWith(editOrder).toMutableList()
        for (exact in listOf(true, false)) {
            val iterator = restFirst.iterator()
            while (iterator.hasNext()) {
                val a = iterator.next()
                val b = restSecond.firstOrNull { b ->
                    if (exact) a.start == b.start && a.end == b.end && Alignment.same(a.replacement, b.replacement)
                    else near(a, b) && sameEffect(original, listOf(a), listOf(b))
                } ?: continue
                iterator.remove()
                restSecond.remove(b)
                groups += Group(listOf(a), listOf(b))
            }
        }
        for (cluster in clusters(restFirst.map { it to true } + restSecond.map { it to false })) {
            val a = cluster.filter { it.second }.map { it.first }
            val b = cluster.filterNot { it.second }.map { it.first }
            groups += when {
                a.isEmpty() -> b.map { Group(emptyList(), listOf(it)) }
                b.isEmpty() -> a.map { Group(listOf(it), emptyList()) }
                else -> listOf(Group(a, b))
            }
        }
        return groups
    }

    /** Connected runs of edits whose spans overlap or meet, each edit tagged with its answer. */
    private fun clusters(edits: List<Pair<Edit, Boolean>>): List<List<Pair<Edit, Boolean>>> {
        val sorted = edits.sortedWith(compareBy({ it.first.start }, { it.first.end }))
        val result = mutableListOf<MutableList<Pair<Edit, Boolean>>>()
        var reach = -1
        for (edit in sorted) {
            if (result.isEmpty() || edit.first.start > reach) {
                result += mutableListOf(edit)
                reach = edit.first.end
            } else {
                result.last() += edit
                reach = maxOf(reach, edit.first.end)
            }
        }
        return result
    }

    /** Step 4: a fix in one answer and the same change as a rewording in the other agree. */
    private fun acrossKinds(original: String, groups: List<Group>): List<Group> {
        val result = groups.toMutableList()
        for (lone in groups.filter { it.second.isEmpty() }) {
            val kind = lone.first.first().kind
            val partner = result.firstOrNull { other ->
                other.first.isEmpty() && other.second.first().kind != kind &&
                    lone.start <= other.end && other.start <= lone.end &&
                    sameEffect(original, lone.first, other.second)
            } ?: continue
            result.remove(lone)
            result.remove(partner)
            result += Group(lone.first, partner.second)
        }
        return result
    }

    /** Spans that overlap or meet, including an insertion at either end of the other. */
    private fun near(a: Edit, b: Edit) = a.start <= b.end && b.start <= a.end

    /** Whether [a] and [b] each turn the stretch of [original] they cover together into the same text. */
    private fun sameEffect(original: String, a: List<Edit>, b: List<Edit>): Boolean {
        val start = (a + b).minOf { it.start }
        val end = (a + b).maxOf { it.end }
        return Alignment.same(textOf(original, a, start, end), textOf(original, b, start, end))
    }

    /** [original] from [start] to [end] with [edits] (all inside it) applied. */
    private fun textOf(original: String, edits: List<Edit>, start: Int, end: Int): String =
        render(original.substring(start, end), edits.map { it.copy(start = it.start - start, end = it.end - start) }).text

    private val PIECES = Regex("""\s+|\S+""")

    /** Drops whole words and spaces that every present text shares at its start or end. */
    internal fun shared(from: String, first: String?, second: String?): ShownChange {
        val texts = listOfNotNull(from, first, second).map { text -> PIECES.findAll(text).map { it.value }.toList() }
        val shortest = texts.minOf { it.size }
        var lead = 0
        while (lead < shortest && texts.all { it[lead] == texts[0][lead] }) lead++
        var trail = 0
        while (trail < shortest - lead && texts.all { it[it.size - 1 - trail] == texts[0][texts[0].size - 1 - trail] }) trail++
        // Nothing differs (an agreement already equal to the original can't happen, but stay whole).
        if (lead == shortest && texts.all { it.size == shortest }) return ShownChange(from, first, second)
        fun cut(text: String?): String? = text?.let {
            val pieces = PIECES.findAll(it).map { piece -> piece.value }.toList()
            pieces.subList(lead, pieces.size - trail).joinToString("")
        }
        return ShownChange(cut(from)!!, cut(first), cut(second))
    }
}
