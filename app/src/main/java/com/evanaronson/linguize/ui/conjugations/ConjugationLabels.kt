package com.evanaronson.linguize.ui.conjugations

import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.core.morphology.Person
import com.evanaronson.linguize.core.morphology.Tense
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@StringRes
internal fun Tense.labelResource(language: String): Int = when (this) {
    Tense.Present -> R.string.lab_conjugations_tense_present
    Tense.Preterite -> if (language == "ca") R.string.lab_conjugations_tense_simple_past else R.string.lab_conjugations_tense_preterite
    Tense.Imperfect -> R.string.lab_conjugations_tense_imperfect
    Tense.Future -> R.string.lab_conjugations_tense_future
    Tense.Conditional -> R.string.lab_conjugations_tense_conditional
    Tense.PresentSubjunctive -> R.string.lab_conjugations_tense_present_subjunctive
    Tense.ImperfectSubjunctive -> R.string.lab_conjugations_tense_imperfect_subjunctive
    Tense.Imperative -> R.string.lab_conjugations_tense_imperative
    Tense.Infinitive -> R.string.lab_conjugations_tense_infinitive
    Tense.Gerund -> R.string.lab_conjugations_tense_gerund
    Tense.Participle -> R.string.lab_conjugations_tense_participle
    Tense.Perfect -> R.string.lab_conjugations_tense_perfect
    Tense.Pluperfect -> R.string.lab_conjugations_tense_pluperfect
    Tense.FuturePerfect -> R.string.lab_conjugations_tense_future_perfect
    Tense.ConditionalPerfect -> R.string.lab_conjugations_tense_conditional_perfect
    Tense.PerfectSubjunctive -> R.string.lab_conjugations_tense_perfect_subjunctive
    Tense.PluperfectSubjunctive -> R.string.lab_conjugations_tense_pluperfect_subjunctive
    Tense.PeriphrasticPast -> R.string.lab_conjugations_tense_periphrastic_past
}

@get:StringRes
internal val Person.labelResource: Int
    get() = when (this) {
        Person.S1 -> R.string.lab_conjugations_person_1s
        Person.S2 -> R.string.lab_conjugations_person_2s
        Person.S3 -> R.string.lab_conjugations_person_3s
        Person.P1 -> R.string.lab_conjugations_person_1p
        Person.P2 -> R.string.lab_conjugations_person_2p
        Person.P3 -> R.string.lab_conjugations_person_3p
    }

/**
 * The column header: the subject pronoun in the language itself (sample text, not translated).
 * Usted and ustedes, vostè and vostès, share the third person columns.
 */
internal fun Person.pronoun(language: String): String = when (language) {
    "ca" -> listOf("jo", "tu", "ell", "nos.", "vos.", "ells")
    else -> listOf("yo", "tú", "él", "nos.", "vos.", "ellos")
}[ordinal]

/** The full pronouns, for the cell page. */
internal fun Person.pronouns(language: String): String = when (language) {
    "ca" -> listOf("jo", "tu", "ell, ella, vostè", "nosaltres", "vosaltres", "ells, elles, vostès")
    else -> listOf("yo", "tú", "él, ella, usted", "nosotros", "vosotros", "ellos, ellas, ustedes")
}[ordinal]

/** A use count: whole numbers as they are, shares to one decimal ("2.5"). */
internal fun formatUses(weight: Double, locale: Locale): String {
    val rounded = (weight * 10).roundToInt() / 10.0
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (abs(rounded - rounded.roundToInt()) < 1e-9) 0 else 1
    }
    return format.format(rounded)
}

/** The plural quantity for a use count. */
internal fun usesQuantity(weight: Double): Int = (weight * 10).roundToInt().let { if (it == 10) 1 else maxOf(2, it / 10) }
