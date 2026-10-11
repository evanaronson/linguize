package com.evanaronson.linguize.menu

/**
 * How many checks the selection menu starts in a sliding [windowMillis]. The menu entry is
 * exported, so any app can open it with text and spend the user's API quota without a tap;
 * this caps that at [perApp] checks per calling app and [overall] checks in all.
 *
 * Apps that open the entry without asking for a result have no known package and share one
 * allowance. Counts live in memory, so they start over when the process does.
 */
class CheckRateLimit(
    private val perApp: Int = 6,
    private val overall: Int = 12,
    private val windowMillis: Long = 60_000,
    /** Milliseconds on a clock that never goes back. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Start times of allowed checks, oldest first, with the app that asked. */
    private val started = ArrayDeque<Pair<Long, String?>>()

    /** Whether [callingPackage] (null when unknown) may start a check now; counts it if so. */
    @Synchronized
    fun tryAcquire(callingPackage: String?): Boolean {
        val time = now()
        while (started.isNotEmpty() && time - started.first().first >= windowMillis) started.removeFirst()
        if (started.size >= overall) return false
        if (started.count { it.second == callingPackage } >= perApp) return false
        started.addLast(time to callingPackage)
        return true
    }

    companion object {
        /** The one limit the app process shares. */
        val menu = CheckRateLimit()
    }
}
