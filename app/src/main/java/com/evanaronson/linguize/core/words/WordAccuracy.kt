package com.evanaronson.linguize.core.words

/**
 * How often a word was used right: right uses over all uses, as a whole percent.
 *
 * A use is right outside every corrected span and wrong inside one (Linguize flagged it,
 * whether or not the writer accepted the fix). The two never overlap, so right + wrong is
 * every use, and either can be the larger: "12 right, 3 wrong" is 80% right.
 */
object WordAccuracy {
    /**
     * 0 to 100, rounded, or null with no uses. Never 100 while any use was wrong, and never 0
     * while any use was right, so rounding can't hide either.
     */
    fun percent(right: Int, wrong: Int): Int? {
        if (right < 0 || wrong < 0 || right + wrong == 0) return null
        val rounded = Math.round(right * 100.0 / (right + wrong)).toInt()
        return when {
            wrong > 0 && rounded >= 100 -> 99
            right > 0 && rounded <= 0 -> 1
            else -> rounded
        }
    }
}
