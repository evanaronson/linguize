package com.evanaronson.linguize.ui.patterns

import androidx.annotation.StringRes
import com.evanaronson.linguize.R
import com.evanaronson.linguize.history.patterns.Basis
import com.evanaronson.linguize.history.patterns.PatternKind
import com.evanaronson.linguize.history.patterns.Trend

/** Display names stay here; the analyser keeps only stable tokens. */
@get:StringRes
internal val PatternKind.labelResource: Int
    get() = when (this) {
        PatternKind.Accents -> R.string.lab_patterns_kind_accents
        PatternKind.InvertedMarks -> R.string.lab_patterns_kind_inverted_marks
        PatternKind.Capitals -> R.string.lab_patterns_kind_capitals
        PatternKind.Punctuation -> R.string.lab_patterns_kind_punctuation
        PatternKind.Elision -> R.string.lab_patterns_kind_elision
        PatternKind.TenirQue -> R.string.lab_patterns_kind_tenir_que
        PatternKind.SerEstar -> R.string.lab_patterns_kind_ser_estar
        PatternKind.PorPara -> R.string.lab_patterns_kind_por_para
        PatternKind.Gender -> R.string.lab_patterns_kind_gender
        PatternKind.Number -> R.string.lab_patterns_kind_number
        PatternKind.Agreement -> R.string.lab_patterns_kind_agreement
        PatternKind.Articles -> R.string.lab_patterns_kind_articles
        PatternKind.Prepositions -> R.string.lab_patterns_kind_prepositions
        PatternKind.Subjunctive -> R.string.lab_patterns_kind_subjunctive
        PatternKind.VerbForms -> R.string.lab_patterns_kind_verb_forms
        PatternKind.Spelling -> R.string.lab_patterns_kind_spelling
        PatternKind.Other -> R.string.lab_patterns_kind_other
    }

@get:StringRes
internal val PatternKind.aboutResource: Int
    get() = when (this) {
        PatternKind.Accents -> R.string.lab_patterns_about_accents
        PatternKind.InvertedMarks -> R.string.lab_patterns_about_inverted_marks
        PatternKind.Capitals -> R.string.lab_patterns_about_capitals
        PatternKind.Punctuation -> R.string.lab_patterns_about_punctuation
        PatternKind.Elision -> R.string.lab_patterns_about_elision
        PatternKind.TenirQue -> R.string.lab_patterns_about_tenir_que
        PatternKind.SerEstar -> R.string.lab_patterns_about_ser_estar
        PatternKind.PorPara -> R.string.lab_patterns_about_por_para
        PatternKind.Gender -> R.string.lab_patterns_about_gender
        PatternKind.Number -> R.string.lab_patterns_about_number
        PatternKind.Agreement -> R.string.lab_patterns_about_agreement
        PatternKind.Articles -> R.string.lab_patterns_about_articles
        PatternKind.Prepositions -> R.string.lab_patterns_about_prepositions
        PatternKind.Subjunctive -> R.string.lab_patterns_about_subjunctive
        PatternKind.VerbForms -> R.string.lab_patterns_about_verb_forms
        PatternKind.Spelling -> R.string.lab_patterns_about_spelling
        PatternKind.Other -> R.string.lab_patterns_about_other
    }

@get:StringRes
internal val Trend.labelResource: Int
    get() = when (this) {
        Trend.Fewer -> R.string.lab_patterns_trend_fewer
        Trend.Steady -> R.string.lab_patterns_trend_steady
        Trend.More -> R.string.lab_patterns_trend_more
        Trend.Unknown -> R.string.lab_patterns_trend_unknown
    }

@get:StringRes
internal val Basis.labelResource: Int
    get() = when (this) {
        Basis.Exact -> R.string.lab_patterns_basis_exact
        Basis.Words -> R.string.lab_patterns_basis_words
        Basis.Reason -> R.string.lab_patterns_basis_reason
    }
