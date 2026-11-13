package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: averages that one unusually slow message can't skew. A message left open
 * within the idle caps can still take ten times the usual; a mean moves with it, a median
 * doesn't. Totals stay true sums; only "per" figures use these.
 */
object Median {
    /** The median of [values] (the mean of the middle two for an even count); null when empty. */
    fun of(values: List<Long>): Long? = weighted(values.map { it.toDouble() to 1.0 })?.let(Math::round)

    /**
     * The weighted median of `value to weight` pairs: the value at which half the total weight
     * lies on either side (the mean of the two values at an exact split). Pairs without a
     * positive weight are ignored; null when nothing is left.
     *
     * For a rate, each message's own rate weighted by its size (words, changes): "half your
     * words were written at this speed or faster". One message can move it only by carrying
     * half the weight, unlike total time over total words, which a single slow message drags.
     */
    fun weighted(pairs: List<Pair<Double, Double>>): Double? {
        val sorted = pairs.filter { it.second > 0.0 && !it.first.isNaN() }.sortedBy { it.first }
        if (sorted.isEmpty()) return null
        val half = sorted.sumOf { it.second } / 2
        var below = 0.0
        sorted.forEachIndexed { index, (value, weight) ->
            below += weight
            if (below > half + EPSILON) return value
            if (below >= half - EPSILON) return sorted.getOrNull(index + 1)?.let { (value + it.first) / 2 } ?: value
        }
        return sorted.last().first
    }

    /** Per unit of [size], over [items] whose size is positive: the [weighted] median of each one's own rate. */
    fun <T> rate(items: List<T>, ms: (T) -> Long, size: (T) -> Int): Long? =
        weighted(items.filter { size(it) > 0 }.map { ms(it).toDouble() / size(it) to size(it).toDouble() })?.let(Math::round)

    private const val EPSILON = 1e-9
}
