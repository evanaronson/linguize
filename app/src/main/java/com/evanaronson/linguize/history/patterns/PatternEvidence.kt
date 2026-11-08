package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult

/**
 * Reads the newest visible saved checks, through History's own read APIs, for the analyser.
 * Uses the same eligibility as the History list, so a hidden or deleted check is never read.
 * Returns null when a read failed, so the caller can retry rather than show partial patterns.
 * A check deleted between the page and its detail read is skipped.
 */
suspend fun HistoryStore.patternEvidence(limit: Int = MAX_CHECKS, pageSize: Int = 50): List<SessionDetail>? {
    val ids = mutableListOf<String>()
    var cursor: HistoryCursor? = null
    do {
        val page = page(cursor, minOf(pageSize, limit - ids.size)) ?: return null
        ids += page.rows.map { it.id }
        cursor = page.next
    } while (cursor != null && ids.size < limit)
    return ids.distinct().mapNotNull { id ->
        when (val result = detail(id)) {
            is SessionDetailResult.Found -> result.detail
            SessionDetailResult.Missing -> null
            SessionDetailResult.Failed -> return null
        }
    }
}

/** The newest saved checks the patterns read; older ones are left out to keep the read quick. */
const val MAX_CHECKS = 400
