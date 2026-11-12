package com.evanaronson.linguize.history.patterns

import com.evanaronson.linguize.history.HistoryEvidenceReader
import com.evanaronson.linguize.history.HistoryEvidenceResult
import com.evanaronson.linguize.history.HistoryStore
import com.evanaronson.linguize.history.SessionDetail

/** Patterns owns its 400-check window; the shared reader owns the immutable History read. */
suspend fun HistoryEvidenceReader.patternEvidence(limit: Int = MAX_CHECKS): HistoryEvidenceResult = read(limit)

/** Nullable convenience for callers that only need details; failure never becomes an empty list. */
suspend fun HistoryStore.patternEvidence(limit: Int = MAX_CHECKS): List<SessionDetail>? =
    when (val result = HistoryEvidenceReader(this).patternEvidence(limit)) {
        is HistoryEvidenceResult.Ready -> result.snapshot.details
        HistoryEvidenceResult.Failed -> null
    }

/** The newest saved checks the patterns read; older ones are left out to keep the read quick. */
const val MAX_CHECKS = 400
