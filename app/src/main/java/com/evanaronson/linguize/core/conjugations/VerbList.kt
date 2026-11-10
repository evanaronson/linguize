package com.evanaronson.linguize.core.conjugations

import com.evanaronson.linguize.core.morphology.Tense
import com.evanaronson.linguize.core.words.WordViews
import java.text.Collator
import java.util.Locale

/** How a list of verbs is ordered. */
enum class VerbSort(val token: String) {
    /** Most uses first: the page's preview order. */
    MostUsed("used"),
    /** The verbs used in the most tense and person forms first. */
    MostForms("forms"),
    Alphabetical("az"),
    ;

    companion object {
        fun read(token: String?): VerbSort? = entries.firstOrNull { it.token == token }
    }
}

/**
 * One verb in a list: [weight] is its uses (in [VerbList.rows]' cell when there is one),
 * [cells] the tense and person forms it was used in, [tenses] in the grid's order.
 */
data class VerbRow(val lemma: String, val weight: Double, val cells: Int, val tenses: List<Tense>, val lastUsed: Long)

/** A capped first look at a list: the first [shown] of [total]. */
data class VerbPreview(val shown: List<VerbRow>, val total: Int) {
    val more: Boolean get() = total > shown.size
}

/**
 * Lab, Conjugations page: the verbs of a report, for the capped lists on Your conjugations and
 * a cell's page and for the full, searchable list behind "Show all". Counts and sentences
 * for a verb live on its Words page; these rows carry what Words can't: forms and tenses.
 */
object VerbList {
    /** How many verbs a section shows before "Show all". */
    const val PREVIEW = 5

    /** The sorts a list offers: within a cell every verb has one form, so forms don't order it. */
    fun sorts(cell: Cell?): List<VerbSort> =
        if (cell == null) listOf(VerbSort.MostUsed, VerbSort.MostForms, VerbSort.Alphabetical) else listOf(VerbSort.MostUsed, VerbSort.Alphabetical)

    /** Every verb of [report], or only those used in [cell] with their uses there; most used first. */
    fun rows(report: ConjugationReport, cell: Cell? = null): List<VerbRow> {
        val order = report.rows + ConjugationMap.NON_FINITE
        val inCell = cell?.let { report.stats(it)?.verbs ?: return emptyList() }
        return report.verbs.mapNotNull { verb ->
            val weight = if (inCell == null) verb.weight else inCell[verb.lemma] ?: return@mapNotNull null
            VerbRow(verb.lemma, weight, verb.cells.size, verb.tenses.sortedBy { order.indexOf(it) }, verb.lastUsed)
        }.sortedWith(byUse)
    }

    /** The first [limit] of [rows] as they're ordered. */
    fun preview(rows: List<VerbRow>, limit: Int = PREVIEW): VerbPreview = VerbPreview(rows.take(limit), rows.size)

    /**
     * [rows] matching [query] (accent-insensitive, anywhere in the infinitive), ordered by
     * [sort]. A–Z follows [locale]'s collation.
     */
    fun arrange(rows: List<VerbRow>, sort: VerbSort, query: String = "", locale: Locale = Locale.ROOT): List<VerbRow> {
        val needle = WordViews.fold(query.trim())
        val matching = if (needle.isEmpty()) rows else rows.filter { WordViews.fold(it.lemma).contains(needle) }
        return when (sort) {
            VerbSort.MostUsed -> matching.sortedWith(byUse)
            VerbSort.MostForms -> matching.sortedWith(compareByDescending<VerbRow> { it.cells }.then(byUse))
            VerbSort.Alphabetical -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
                matching.sortedWith { a, b -> collator.compare(a.lemma, b.lemma).takeIf { it != 0 } ?: a.lemma.compareTo(b.lemma) }
            }
        }
    }

    private val byUse = compareByDescending<VerbRow> { it.weight }.thenByDescending { it.lastUsed }.thenBy { it.lemma }
}
