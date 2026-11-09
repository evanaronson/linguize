package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: which stored measurements no longer have their History session (deleted
 * from History, History cleared, or a write that never landed). Measurements younger than
 * [GRACE_MS] are kept: their session's write may still be on its way.
 */
object TimeSweep {
    const val GRACE_MS = 10 * 60_000L

    fun orphans(records: List<TimeRecord>, sessions: Set<String>, now: Long): List<String> =
        records.filter { it.sessionId !in sessions && now - it.at > GRACE_MS }.map { it.sessionId }
}
