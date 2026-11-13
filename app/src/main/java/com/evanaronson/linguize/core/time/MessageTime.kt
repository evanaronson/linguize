package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: one message's time, for the line under it in the chat and on its History
 * page. Writing is measured in the chat; for another app it's [WritingEstimator]'s estimate, and
 * [writingEstimated] says so wherever it's shown.
 */
data class MessageTime(val writingMs: Long, val writingEstimated: Boolean, val reviewMs: Long) {
    companion object {
        /** The time of History session [sessionId] among [records] (every record, for the estimate); null when none was recorded. */
        fun of(sessionId: String, records: List<TimeRecord>): MessageTime? {
            val record = records.firstOrNull { it.sessionId == sessionId } ?: return null
            val writing = record.writingMs ?: WritingEstimator(records).writingOf(record)
            return MessageTime(writing, record.writingMs == null, record.reviewMs)
        }
    }
}
