package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.history.HistoryCursor
import com.evanaronson.linguize.history.HistoryPage
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.ReuseKey
import com.evanaronson.linguize.history.SessionDetail
import com.evanaronson.linguize.history.SessionDetailResult
import com.evanaronson.linguize.history.SessionRecord
import com.evanaronson.linguize.history.SessionSummary
import com.evanaronson.linguize.history.SuggestionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.OutputStream

/** In-memory visible history for pattern tests. Records which reads were made. */
internal class FakePatternHistory(details: List<SessionDetail> = emptyList()) : HistoryStore {
    val sessions = linkedMapOf<String, SessionDetail>().apply { details.forEach { put(it.session.id, it) } }
    val revisions = MutableStateFlow(0L)
    var failPage = false
    var failDetail: String? = null
    val detailReads = mutableListOf<String>()

    /** Deleted between a page read and its detail read. */
    var vanishOnDetail: String? = null

    override suspend fun save(session: SessionRecord) = Unit
    override suspend fun close(session: SessionRecord, suggestions: List<SuggestionRecord>) = Unit
    override suspend fun markAbandoned(now: Long) = Unit
    override fun count(): Flow<Int> = flowOf(sessions.size)
    override fun since(): Flow<Long?> = flowOf(sessions.values.minOfOrNull { it.session.startedAt })
    override fun recent(limit: Int): Flow<List<SessionSummary>> = flowOf(ordered().take(limit).map(::summary))
    override fun revisions(): Flow<Long> = revisions

    override suspend fun page(before: HistoryCursor?, limit: Int): HistoryPage? {
        if (failPage) return null
        val all = ordered()
        val start = before?.let { cursor -> all.indexOfFirst { it.session.id == cursor.id } + 1 } ?: 0
        val rows = all.drop(start).take(limit)
        val next = if (start + rows.size < all.size) rows.lastOrNull()?.let { HistoryCursor(it.session.startedAt, it.session.id) } else null
        return HistoryPage(rows.map(::summary), next)
    }

    override suspend fun detail(id: String): SessionDetailResult {
        detailReads += id
        if (id == failDetail) return SessionDetailResult.Failed
        if (id == vanishOnDetail) sessions.remove(id)
        return sessions[id]?.let { SessionDetailResult.Found(it) } ?: SessionDetailResult.Missing
    }

    override suspend fun reusable(key: ReuseKey, since: Long): SessionDetail? = null

    override suspend fun delete(id: String): Boolean {
        sessions.remove(id)
        revisions.value++
        return true
    }

    override suspend fun clear() {
        sessions.clear()
        revisions.value++
    }

    override suspend fun export(out: OutputStream): Boolean = false

    private fun ordered() = sessions.values.sortedWith(
        compareByDescending<SessionDetail> { it.session.startedAt }.thenByDescending { it.session.id },
    )

    private fun summary(detail: SessionDetail) = SessionSummary(
        id = detail.session.id, startedAt = detail.session.startedAt, origin = detail.session.origin, hostApp = null,
        text = detail.session.text, outcome = detail.session.outcome, fixes = 0, rewordings = 0, taken = 0,
    )
}
